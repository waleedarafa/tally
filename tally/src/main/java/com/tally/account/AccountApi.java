package com.tally.account;

import java.util.Optional;
import java.util.UUID;

/**
 * Customer accounts. Consumed as conformist by the other modules (FR-014). An account is opened only for a user, by
 * {@code POST /v1/accounts}, which links the two (ADR-032; WAR-84): no other module opens one.
 */
public interface AccountApi {

    Optional<AccountView> get(UUID accountId);

    /**
     * Stores the provider token for the account's payment method, collected by the provider's
     * hosted card form. Card numbers never reach Tally (FR-015).
     *
     * @throws UnknownAccountException if there is no such account
     */
    void recordPaymentToken(UUID accountId, String paymentToken);
}
