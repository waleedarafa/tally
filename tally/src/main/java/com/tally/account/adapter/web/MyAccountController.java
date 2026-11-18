package com.tally.account.adapter.web;

import com.tally.account.application.AccountUsers;
import com.tally.platform.Caller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/me/account} (docs/api/openapi.yaml; Slice 4): the caller's account, found from the token's subject,
 * for an app on a new phone and for the streaming platform. 404 while the user has none. Read only: no key.
 */
@RestController
class MyAccountController {

    private final AccountUsers users;

    MyAccountController(AccountUsers users) {
        this.users = users;
    }

    @GetMapping("/v1/me/account")
    ResponseEntity<AccountController.AccountResponse> mine(Caller caller) {
        return ResponseEntity.of(users.accountOf(caller.id()).map(AccountController.AccountResponse::of));
    }
}
