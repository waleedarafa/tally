package com.tally.account;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/** Customer accounts. Consumed as conformist by the other modules (ADR-014). */
public interface AccountApi {

    AccountView open(Currency currency, ZoneId timeZone);

    Optional<AccountView> get(UUID accountId);

    /**
     * Stores the provider token for the account's payment method, collected by the provider's
     * hosted card form. Card numbers never reach Tally (ADR-015).
     *
     * @throws UnknownAccountException if there is no such account
     */
    void recordPaymentToken(UUID accountId, String paymentToken);
}
