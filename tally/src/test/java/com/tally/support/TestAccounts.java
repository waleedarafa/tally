package com.tally.support;

import com.tally.account.AccountView;
import com.tally.account.application.AccountUsers;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;

/**
 * Test only: opens accounts as {@code POST /v1/accounts} does, linked to a user (ADR-032; WAR-84). {@code AccountApi}
 * opens none since Slice 4 PR-4: an account with no user could not be reached with a token. A test that only needs an
 * account gets one for a new, random user; a test that then calls the API as a household opens it for that household.
 */
@TestComponent
public class TestAccounts {

    private final AccountUsers users;

    public TestAccounts(AccountUsers users) {
        this.users = users;
    }

    /** An account for a new, random user. */
    public AccountView open(Currency currency, ZoneId timeZone) {
        return users.openFor(UUID.randomUUID().toString(), currency, timeZone).account();
    }

    /** The account of {@code household}, as its own {@code POST /v1/accounts} would open it. */
    public AccountView openFor(TestTokens.Household household, Currency currency, ZoneId timeZone) {
        return users.openFor(household.user(), currency, timeZone).account();
    }
}
