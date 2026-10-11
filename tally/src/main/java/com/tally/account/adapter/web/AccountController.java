package com.tally.account.adapter.web;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.platform.IdempotencyKeys;
import com.tally.platform.IdempotencyStore;
import com.tally.platform.IdempotentEndpoints;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /v1/accounts} (docs/api/openapi.yaml). Opening an account changes state, so it requires an
 * {@code Idempotency-Key} (CLAUDE.md rule 13; ADR-019), handled by the platform's shared mechanism.
 */
@RestController
@RequestMapping("/v1/accounts")
class AccountController {

    /**
     * No account exists before it is opened, so until callers are authenticated every key of this endpoint
     * belongs to one scope: two clients that choose the same key value share it.
     */
    static final String UNAUTHENTICATED = "unauthenticated";

    record OpenAccount(String currency, String timeZone) {
    }

    record AccountResponse(UUID id, String currency, String timeZone) {
        static AccountResponse of(AccountView view) {
            return new AccountResponse(view.id(), view.currency().getCurrencyCode(), view.timeZone().getId());
        }
    }

    private final AccountApi accounts;
    private final IdempotentEndpoints endpoints;
    private final IdempotencyStore keys;
    private final JsonMapper json;

    AccountController(AccountApi accounts, IdempotentEndpoints endpoints, IdempotencyKeys idempotencyKeys,
            JsonMapper json) {
        this.accounts = accounts;
        this.endpoints = endpoints;
        this.keys = idempotencyKeys.register("account.idempotency_key");
        this.json = json;
    }

    @PostMapping
    ResponseEntity<String> open(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody OpenAccount request) {
        return endpoints.handle(keys, UNAUTHENTICATED, key, request, () -> {
            AccountView opened = accounts.open(Currency.getInstance(request.currency()), ZoneId.of(request.timeZone()));
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(AccountResponse.of(opened)));
        }, failure -> failure instanceof IllegalArgumentException || failure instanceof NullPointerException
                || failure instanceof DateTimeException
                ? Optional.of(endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not an account",
                        "currency must be an ISO 4217 code and timeZone an IANA time zone."))
                : Optional.empty());
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return accounts.get(id).map(AccountResponse::of).orElseThrow(() -> new UnknownAccountException(id));
    }

    @ExceptionHandler(UnknownAccountException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() {
    }
}
