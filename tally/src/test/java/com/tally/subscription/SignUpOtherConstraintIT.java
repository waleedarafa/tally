package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionPeriod;
import com.tally.subscription.domain.Subscriptions;
import com.tally.support.TestAccounts;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WAR-85: a database failure at sign-up, other than the one-live-subscription guard, must not be
 * reported as "already subscribed" (CLAUDE.md rule 11). The example is a period that ends on the
 * day it starts, which fails {@code end_date > start_date}.
 */
class SignUpOtherConstraintIT extends AbstractIntegrationTest {

    @Autowired
    Subscriptions subscriptions;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void aPeriodThatEndsOnTheDayItStartsIsNotAlreadySubscribed() throws Exception {
        UUID account = testAccounts.open(Currency.getInstance("USD"), ZoneOffset.UTC).id();
        Subscription subscription = Subscription.start(account, "BASIC", 1, clock.instant(), ZoneOffset.UTC);
        LocalDate start = subscription.currentPeriod().start();
        setPeriods(subscription, List.of(new SubscriptionPeriod(1, start, start)));

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            if (!subscriptions.addIfNoneLive(subscription)) {
                throw new AlreadySubscribedException(account);
            }
        })).isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(AlreadySubscribedException.class);

        assertThat(subscriptions.hasLive(account)).isFalse();
    }

    static void setPeriods(Subscription subscription, List<SubscriptionPeriod> periods) throws Exception {
        Field field = Subscription.class.getDeclaredField("periods");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SubscriptionPeriod> current = (List<SubscriptionPeriod>) field.get(subscription);
        current.clear();
        current.addAll(periods);
    }
}
