package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.UnknownAccountException;
import com.tally.offers.UnknownPlanException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class SubscriptionApiIT extends AbstractIntegrationTest {

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    AccountApi accounts;

    @Autowired
    ApplicationEvents events;

    UUID account() {
        return accounts.open(Currency.getInstance("USD"), ZoneId.of("America/New_York")).id();
    }

    @Test
    void subscribingStartsTheSubscriptionAndPublishesAThinEvent() {
        UUID account = account();

        SubscriptionView started = subscriptions.subscribe(account, "STANDARD");

        assertThat(started.startedAt()).isEqualTo(clock.instant());
        assertThat(subscriptions.timeline(started.id()))
                .containsExactly(new TimelineEntry(1, SubscriptionChangeType.STARTED, "STANDARD", clock.instant()));
        assertThat(events.stream(SubscriptionChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.accountId()).isEqualTo(account);
            assertThat(e.subscriptionId()).isEqualTo(started.id());
            assertThat(e.type()).isEqualTo(SubscriptionChangeType.STARTED);
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.occurredAt()).isEqualTo(clock.instant());
            assertThat(e.eventId()).isNotNull();
        });
    }

    @Test
    void anAccountHasAtMostOneSubscription() {
        UUID account = account();
        subscriptions.subscribe(account, "BASIC");

        assertThatThrownBy(() -> subscriptions.subscribe(account, "PREMIUM"))
                .isInstanceOf(AlreadySubscribedException.class);
        assertThat(events.stream(SubscriptionChanged.class)).hasSize(1);
    }

    @Test
    void refusesUnknownAccountsAndPlans() {
        assertThatThrownBy(() -> subscriptions.subscribe(UUID.randomUUID(), "BASIC"))
                .isInstanceOf(UnknownAccountException.class);
        assertThatThrownBy(() -> subscriptions.subscribe(account(), "GOLD"))
                .isInstanceOf(UnknownPlanException.class);
        assertThat(events.stream(SubscriptionChanged.class)).isEmpty();
    }
}
