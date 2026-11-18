package com.tally.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.tally.account.Standing;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.payment.AttemptState;
import com.tally.shared.DomainEvent;
import com.tally.shared.Money;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slice 3 PR-5, "pay now" (decision 2; proposed in ADR-037): the household asks Tally to try the payment again, in any
 * state with a debt open. Collections asks Invoicing again for every unpaid invoice of the case, with the case's next
 * retry number, whose row is the permanent guard (rule 11); no transaction is open during the call to the provider
 * (rule 6). A decline changes no state: the timers decide.
 */
@TestPropertySource(properties = "tally.test.context=pay-now")
class PayNowIT extends AbstractReconcilingTest {

    @Autowired
    Collections collections;

    @MockitoSpyBean
    InvoicingApi invoicingSpy;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    DataSource dataSource;

    @AfterEach
    void providerNormal() {
        FakeProvider.mode("NORMAL");
    }

    UUID account() {
        return testAccounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
    }

    void publish(DomainEvent event) {
        transactions.executeWithoutResult(tx -> events.publishEvent(event));
    }

    void declined(UUID account, UUID invoice) {
        publish(new InvoicePaymentDeclined(UUID.randomUUID(), clock.instant(), account, invoice, "ref-" + invoice,
                Money.of(1599, "USD"), 2L));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));
    }

    CaseView theCase(UUID account) {
        return collections.caseFor(account).orElseThrow();
    }

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    @Test
    void payNowAsksForEveryUnpaidInvoiceWithTheCasesNextRetryNumber() {
        UUID account = account();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        declined(account, first);
        declined(account, second);

        PaymentRequestView asked = collections.requestPayment(account).orElseThrow();
        PaymentRequestView again = collections.requestPayment(account).orElseThrow();

        assertThat(asked.retry()).as("one sequence for each case (#80, option B): the case's first request")
                .isEqualTo(1);
        assertThat(asked.invoiceIds()).containsExactly(first, second);
        assertThat(again.retry()).isEqualTo(2);
        verify(invoicingSpy, timeout(15_000)).requestPaymentAgain(first, 1);
        verify(invoicingSpy, timeout(15_000)).requestPaymentAgain(second, 1);
        verify(invoicingSpy, timeout(15_000)).requestPaymentAgain(second, 2);
        assertThat(theCase(account).status()).as("asking changes no state").isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void withNoDebtThereIsNothingToPay() {
        UUID none = account();
        UUID paid = account();
        UUID invoice = UUID.randomUUID();
        declined(paid, invoice);
        publish(new InvoicePaid(UUID.randomUUID(), clock.instant(), paid, invoice, "ref-" + invoice, 3L));
        Quiesce.untilIdle(jdbc, clock, List.of(paid), Duration.ofSeconds(15));

        assertThat(collections.requestPayment(none)).isEmpty();
        assertThat(collections.requestPayment(paid)).as("the case is closed").isEmpty();
        assertThat(collections.requestPayment(UUID.randomUUID())).as("no such account").isEmpty();
    }

    /** A declined "pay now" changes no state; the timers decide. */
    @Test
    void aDeclinedPayNowChangesNoState() {
        InvoiceView invoice = declinedRenewal("DECLINE_HARD(100)");
        UUID account = invoice.accountId();
        awaitAttempt(invoice.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        Instant first = theCase(account).openedAt();
        // A day at a time, so retry 1's attempt is declined before retry 2 is due.
        for (int day = 1; day <= 8; day++) {
            clock.set(first.plus(Duration.ofDays(day)));
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        }
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OVERDUE);

        assertThat(collections.requestPayment(account).orElseThrow().retry()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("SELECT count(*) FROM"
                + " payment.payment_attempt WHERE reference = ? AND state = 'DECLINED'", Long.class,
                invoice.paymentReference()) == 4);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OVERDUE);
        assertThat(accounts.get(account).orElseThrow().standing()).isEqualTo(Standing.OVERDUE);
    }

    /** Two requests at the same time get two retry numbers: the case's lock orders them (CLAUDE.md rule 7). */
    @Test
    void twoRequestsAtTheSameTimeGetTwoRetryNumbers() throws Exception {
        UUID account = account();
        declined(account, UUID.randomUUID());
        UUID caseId = theCase(account).caseId();
        CompletableFuture<Optional<PaymentRequestView>> one;
        CompletableFuture<Optional<PaymentRequestView>> two;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM collections.collection_case WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, caseId);
                lock.executeQuery();
            }
            one = CompletableFuture.supplyAsync(() -> collections.requestPayment(account));
            two = CompletableFuture.supplyAsync(() -> collections.requestPayment(account));
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }

        List<Integer> retries = new ArrayList<>();
        retries.add(one.get(15, TimeUnit.SECONDS).orElseThrow().retry());
        retries.add(two.get(15, TimeUnit.SECONDS).orElseThrow().retry());
        assertThat(retries).containsExactlyInAnyOrder(1, 2);
    }

    /**
     * Option B of the #80 review (one sequence of retry numbers for each case): an early, declined "pay now" changes
     * nothing in the timetable. Pay now on day 1 is declined; the card works from day 2; the day-3 retry is sent, with
     * the case's next number, and succeeds; the case closes.
     */
    @Test
    void anEarlyDeclinedPayNowLeavesTheDayThreeRetryToBeMade() {
        InvoiceView invoice = declinedRenewal("DECLINE_HARD(100)");
        UUID account = invoice.accountId();
        awaitAttempt(invoice.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        Instant first = theCase(account).openedAt();
        clock.set(first.plus(Duration.ofDays(1)));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        assertThat(collections.requestPayment(account).orElseThrow().retry()).as("the case's first request").isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("SELECT count(*) FROM"
                + " payment.payment_attempt WHERE reference = ? AND state = 'DECLINED'", Long.class,
                invoice.paymentReference()) == 2);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        FakeProvider.mode("NORMAL");
        clock.set(first.plus(Duration.ofDays(2)));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        clock.set(first.plus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        assertThat(jdbc.queryForList("SELECT number || '=' || state FROM payment.payment_attempt WHERE reference = ?"
                + " ORDER BY number", String.class, invoice.paymentReference()))
                .as("the first charge, pay now, the day-3 retry").containsExactly("1=DECLINED", "2=DECLINED", "3=SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT number || ' ' || coalesce(step, 'pay now') FROM collections.case_retry"
                + " WHERE case_id = ? ORDER BY number", String.class, theCase(account).caseId()))
                .containsExactly("1 pay now", "2 DAY_3");
        await().atMost(Duration.ofSeconds(15)).until(() -> theCase(account).status() == CaseStatus.CLOSED);
    }
}
