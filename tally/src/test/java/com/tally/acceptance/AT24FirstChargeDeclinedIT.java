package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.Standing;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AT-24. The first charge is declined (proposed in ADR-025; Slice 3 brief, decision 1, option A). Sign-up, then the
 * first charge is declined. The subscription is ENDED; no Collections case exists; Access says no,
 * {@code NO_ACTIVE_SOURCE}. The same account signs up again with a card that works, and Access says yes.
 *
 * <p>Collections tells the first invoice through {@code InvoicingApi} (decided on #75): the declined invoice is the
 * earliest period of its subscription's invoices.
 */
class AT24FirstChargeDeclinedIT extends AbstractCollectionsAcceptanceTest {

    List<String> subscriptions(UUID account) {
        return jdbc.queryForList("SELECT status || ' ' || coalesce(end_reason, '-') FROM subscription.subscription"
                + " WHERE account_id = ? ORDER BY started_at", String.class, account);
    }

    @Test
    void aDeclinedFirstChargeEndsTheSubscriptionAndTheHouseholdSignsUpAgain() {
        UUID account = openAccount();
        FakeProvider.mode("DECLINE_HARD(100)");
        assertThat(signUp(account)).isEqualTo(201);
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAYMENT_FAILED));
        await().atMost(Duration.ofSeconds(15)).until(() -> subscriptions(account).equals(
                List.of("ENDED FIRST_PAYMENT_DECLINED")));

        Map<String, Object> ended = awaitAnswer(account, Duration.ofSeconds(15),
                a -> "NO_ACTIVE_SOURCE".equals(a.get("reason")));
        assertThat(ended).containsEntry("canWatch", false);
        assertThat(collections.caseFor(account)).as("no case").isEmpty();
        assertThat(standing(account)).isEqualTo(Standing.GOOD);

        FakeProvider.mode("NORMAL");
        assertThat(signUp(account)).as("the same account, a card that works").isEqualTo(201);
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .filter(i -> i.status() == InvoiceStatus.PAID).count() == 1);
        Map<String, Object> again = awaitAnswer(account, Duration.ofSeconds(15),
                a -> Boolean.TRUE.equals(a.get("canWatch")));

        System.out.printf("AT-24: subscriptions %s; Access sequences %d (ended), %d (again)%n",
                subscriptions(account), sequence(ended), sequence(again));
        assertThat(again).containsEntry("reason", "ACTIVE_SOURCE");
        assertThat(sequence(again)).isGreaterThan(sequence(ended));
        assertThat(subscriptions(account)).containsExactlyInAnyOrder("ENDED FIRST_PAYMENT_DECLINED", "ACTIVE -");
        assertThat(collections.caseFor(account)).as("still no case").isEmpty();
        List<InvoiceView> invoices = invoicing.invoicesFor(account);
        assertThat(invoices).extracting(InvoiceView::status)
                .as("the first invoice stays PAYMENT_FAILED (brief: out of scope)")
                .containsExactlyInAnyOrder(InvoiceStatus.PAYMENT_FAILED, InvoiceStatus.PAID);
    }
}
