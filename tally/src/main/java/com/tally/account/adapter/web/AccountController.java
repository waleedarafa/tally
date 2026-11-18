package com.tally.account.adapter.web;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.account.application.AccountUsers;
import com.tally.account.application.UserHasAnAccountException;
import com.tally.platform.Caller;
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
 * {@code Idempotency-Key} (CLAUDE.md rule 13; ADR-019), handled by the platform's shared mechanism. Every request
 * carries a token; its subject is the user (ADR-032), and a user has one account (WAR-84).
 */
@RestController
@RequestMapping("/v1/accounts")
class AccountController {

    record OpenAccount(String currency, String timeZone) {
    }

    record AccountResponse(UUID id, String currency, String timeZone) {
        static AccountResponse of(AccountView view) {
            return new AccountResponse(view.id(), view.currency().getCurrencyCode(), view.timeZone().getId());
        }
    }

    private final AccountApi accounts;
    private final AccountUsers users;
    private final IdempotentEndpoints endpoints;
    private final IdempotencyStore keys;
    private final JsonMapper json;

    AccountController(AccountApi accounts, AccountUsers users, IdempotentEndpoints endpoints,
            IdempotencyKeys idempotencyKeys, JsonMapper json) {
        this.accounts = accounts;
        this.users = users;
        this.endpoints = endpoints;
        this.keys = idempotencyKeys.register("account.idempotency_key");
        this.json = json;
    }

    /**
     * Opens the caller's account: 201 with a new one, or 200 with the one the user has, whatever the key (WAR-84).
     * The body is checked first, so a bad body is 400 either way. Of two "open" requests at the same time, the second
     * rolls back on the link's unique constraint and answers 200 with the first one's account.
     */
    @PostMapping
    ResponseEntity<String> open(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody OpenAccount request, Caller caller) {
        String user = caller.id();
        // The key belongs to the caller (ADR-027): another user who sends the same key value gets the result of
        // that user's own request, never this one's account.
        return endpoints.handle(keys, caller.keyScope(), key, request, () -> {
            AccountUsers.Opened opened = users.openFor(user, Currency.getInstance(request.currency()),
                    ZoneId.of(request.timeZone()));
            return account(opened.opened() ? HttpStatus.CREATED : HttpStatus.OK, opened.account());
        }, failure -> {
            if (failure instanceof UserHasAnAccountException) {
                return users.accountOf(user).map(account -> account(HttpStatus.OK, account));
            }
            return failure instanceof IllegalArgumentException || failure instanceof NullPointerException
                    || failure instanceof DateTimeException
                    ? Optional.of(endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not an account",
                            "currency must be an ISO 4217 code and timeZone an IANA time zone."))
                    : Optional.empty();
        });
    }

    private ResponseEntity<String> account(HttpStatus status, AccountView account) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(AccountResponse.of(account)));
    }

    /** The caller's own account; any other answers 404, as an account that does not exist (CLAUDE.md rule 16). */
    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id, Caller caller) {
        return accounts.get(id).filter(account -> caller.owns(account.id())).map(AccountResponse::of)
                .orElseThrow(() -> new UnknownAccountException(id));
    }

    @ExceptionHandler(UnknownAccountException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() {
    }
}
