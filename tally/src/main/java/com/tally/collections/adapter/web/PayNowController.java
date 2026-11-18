package com.tally.collections.adapter.web;

import com.tally.collections.Collections;
import com.tally.collections.PaymentRequestView;
import com.tally.platform.Caller;
import com.tally.platform.IdempotencyKeys;
import com.tally.platform.IdempotencyStore;
import com.tally.platform.IdempotentEndpoints;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/accounts/{accountId}/collections/payment-requests}: "pay now" (Slice 3, decision 2;
 * docs/api/openapi.yaml). The household asks Tally to try the payment again, with the card the account has, in any
 * state with a debt open. 202: Collections asked Invoicing again for every unpaid invoice of the case; the outcome
 * arrives later, as {@code InvoicePaid} or a decline. 404: the account has no debt open.
 *
 * <p>The {@code Idempotency-Key} is required (CLAUDE.md rule 13), scoped by account until callers are authenticated,
 * and handled by the platform's shared mechanism ({@link IdempotentEndpoints}), with Collections' own key table.
 */
@RestController
class PayNowController {

    record PayNowRequest(UUID accountId) {
    }

    record PaymentRequestResponse(UUID caseId, int retry, List<UUID> invoiceIds) {
        static PaymentRequestResponse of(PaymentRequestView view) {
            return new PaymentRequestResponse(view.caseId(), view.retry(), view.invoiceIds());
        }
    }

    private final Collections collections;
    private final IdempotentEndpoints endpoints;
    private final IdempotencyStore keys;
    private final JsonMapper json;

    PayNowController(Collections collections, IdempotentEndpoints endpoints, IdempotencyKeys idempotencyKeys,
            JsonMapper json) {
        this.collections = collections;
        this.endpoints = endpoints;
        this.keys = idempotencyKeys.register("collections.idempotency_key");
        this.json = json;
    }

    @PostMapping("/v1/accounts/{accountId}/collections/payment-requests")
    ResponseEntity<String> payNow(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @PathVariable UUID accountId, Caller caller) {
        if (!caller.owns(accountId)) {
            // Another household's account answers as one with no debt; nothing is asked of the card, no key is
            // claimed (CLAUDE.md rule 16).
            return noDebtOpen();
        }
        return endpoints.handle(keys, accountId.toString(), key, new PayNowRequest(accountId),
                () -> collections.requestPayment(accountId)
                        .map(asked -> ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                                .body(json.writeValueAsString(PaymentRequestResponse.of(asked))))
                        .orElseGet(this::noDebtOpen),
                failure -> Optional.empty());
    }

    private ResponseEntity<String> noDebtOpen() {
        return endpoints.problem(HttpStatus.NOT_FOUND, "about:blank", "No debt open",
                "The account has no unpaid invoice to pay.");
    }
}
