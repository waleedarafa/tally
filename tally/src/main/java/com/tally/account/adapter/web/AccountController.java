package com.tally.account.adapter.web;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    AccountController(AccountApi accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse open(@RequestBody OpenAccount request) {
        return AccountResponse.of(accounts.open(Currency.getInstance(request.currency()), ZoneId.of(request.timeZone())));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return accounts.get(id).map(AccountResponse::of).orElseThrow(() -> new UnknownAccountException(id));
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class, DateTimeException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void badRequest() {
    }

    @ExceptionHandler(UnknownAccountException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() {
    }
}
