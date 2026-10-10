package com.tally.invoicing.adapter.persistence;

import com.tally.invoicing.domain.SubscriptionBilling;
import com.tally.invoicing.domain.SubscriptionBillings;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link SubscriptionBillings}. */
interface JpaSubscriptionBillings extends JpaRepository<SubscriptionBilling, UUID>, SubscriptionBillings {

    @Override
    default void insertIfAbsent(SubscriptionBilling billing) {
        insertRow(billing.subscriptionId(), billing.accountId(), billing.planId(), billing.calendar().anchorDay(),
                billing.startedOn());
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.subscription_billing (subscription_id, account_id, plan_id, anchor_day, started_on)
            VALUES (:subscriptionId, :accountId, :planId, :anchorDay, :startedOn)
            ON CONFLICT (subscription_id) DO NOTHING
            """)
    int insertRow(UUID subscriptionId, UUID accountId, String planId, int anchorDay, LocalDate startedOn);
}
