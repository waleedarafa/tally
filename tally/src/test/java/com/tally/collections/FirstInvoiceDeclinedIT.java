package com.tally.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slice 3 PR-7 (proposed in ADR-025; decision 1, option A): a decline of a subscription's first invoice ends that
 * subscription, with reason {@code FIRST_PAYMENT_DECLINED}, and opens no case. Collections tells the first invoice
 * through {@code InvoicingApi} (decided on #75): the declined invoice is the earliest period of its subscription's
 * invoices. No new field on {@code InvoicePaymentDeclined}.
 */
@TestPropertySource(properties = "tally.test.context=first-invoice-declined")
class FirstInvoiceDeclinedIT extends AbstractReconcilingTest {

    @Autowired
    Collections collections;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @AfterEach
    void providerNormal() {
        FakeProvider.mode("NORMAL");
    }

    List<String> subscriptions(UUID account) {
        return jdbc.queryForList("SELECT status || ' ' || coalesce(end_reason, '-') FROM subscription.subscription"
                + " WHERE account_id = ? ORDER BY status", String.class, account);
    }

    @Test
    void aDeclinedFirstInvoiceEndsItsSubscriptionAndOpensNoCase() {
        FakeProvider.mode("DECLINE_HARD(100)");
        UUID account = subscribedAccount();
        InvoiceView first = firstInvoice(account);
        awaitAttempt(first.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));

        await().atMost(Duration.ofSeconds(15)).until(() -> subscriptions(account)
                .equals(List.of("ENDED FIRST_PAYMENT_DECLINED")));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));
        assertThat(collections.caseFor(account)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collections.retry_queue WHERE payload LIKE ?",
                Long.class, "%" + account + "%")).as("no timetable").isZero();
    }

    /**
     * The household signs up again; then the old first invoice's decline is delivered again (a publication resubmitted
     * late). It ends nothing: the subscription it names has ended, and the new one is another subscription.
     */
    @Test
    void aLateRepeatOfTheOldDeclineLeavesTheNewSubscriptionLive() {
        FakeProvider.mode("DECLINE_HARD(100)");
        UUID account = subscribedAccount();
        InvoiceView first = firstInvoice(account);
        awaitAttempt(first.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> subscriptions(account)
                .equals(List.of("ENDED FIRST_PAYMENT_DECLINED")));
        FakeProvider.mode("NORMAL");
        subscriptions.subscribe(account, "STANDARD");
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));

        transactions.executeWithoutResult(tx -> events.publishEvent(new InvoicePaymentDeclined(UUID.randomUUID(),
                clock.instant(), account, first.id(), first.paymentReference(), first.amount(), 2L)));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));

        assertThat(subscriptions(account)).containsExactly("ACTIVE -", "ENDED FIRST_PAYMENT_DECLINED");
        assertThat(collections.caseFor(account)).isEmpty();
    }
}
