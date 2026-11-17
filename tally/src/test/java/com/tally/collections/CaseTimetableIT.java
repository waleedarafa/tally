package com.tally.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.Standing;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slice 3 PR-4 (brief rules 1, 2 and 5; design.md §5, proposed in ADR-037): the timetable counts from the case's
 * first decline. Day 3 retry 1, day 7 retry 2, one hour later OVERDUE, day 10 BLOCKED, day 30 WRITTEN_OFF, each only
 * if a debt is still open; the standing follows the case in the same transaction.
 *
 * <p>The points carried from the reviews of #76 and #78: a timer acts on its rule, not on one start state, also when
 * it runs late; before it changes the state or the standing it asks Invoicing which invoices are still unpaid; a
 * decline joins a case that is OVERDUE or BLOCKED; {@code InvoicePaid} finds a case that is OVERDUE, BLOCKED or
 * WRITTEN_OFF.
 *
 * <p>Where a test needs no money to move, the account has no subscription, and the test publishes the decline and the
 * payment itself, as Invoicing would, for an invoice that exists only in the event.
 */
@TestPropertySource(properties = "tally.test.context=case-timetable")
class CaseTimetableIT extends AbstractReconcilingTest {

    @Autowired
    Collections collections;

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
        return accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
    }

    void publish(DomainEvent event) {
        transactions.executeWithoutResult(tx -> events.publishEvent(event));
    }

    InvoicePaymentDeclined declined(UUID account, UUID invoice) {
        return new InvoicePaymentDeclined(UUID.randomUUID(), clock.instant(), account, invoice, "ref-" + invoice,
                Money.of(1599, "USD"), 2L);
    }

    InvoicePaid paid(UUID account, UUID invoice) {
        return new InvoicePaid(UUID.randomUUID(), clock.instant(), account, invoice, "ref-" + invoice, 3L);
    }

    /** A case opened now for an invoice that exists only in the event; returns when its first decline happened. */
    Instant caseFor(UUID account, UUID invoice) {
        publish(declined(account, invoice));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));
        return collections.caseFor(account).orElseThrow().openedAt();
    }

    /** Moves the clock to {@code day} days (and two hours) after the first decline, then waits for the work. */
    void day(Instant firstDecline, int day, UUID account) {
        clock.set(firstDecline.plus(Duration.ofDays(day)).plus(Duration.ofHours(2)));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
    }

    /**
     * Moves the clock to noon on the 1st of a month followed by one of 31 days, so a subscription started now renews on
     * the 1st of that long month and renews again after day 30 of a case opened on that renewal. The clock is the
     * context's: earlier tests of the class have moved it.
     */
    void atTheStartOfALongMonth() {
        LocalDate first = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1);
        while (first.plusMonths(1).lengthOfMonth() != 31) {
            first = first.plusMonths(1);
        }
        clock.set(first.atTime(12, 0).toInstant(ZoneOffset.UTC));
    }

    CaseView theCase(UUID account) {
        return collections.caseFor(account).orElseThrow();
    }

    Standing standing(UUID account) {
        return accounts.get(account).orElseThrow().standing();
    }

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    @Test
    void eachStepChangesTheCaseAndTheStandingOnItsDay() {
        UUID account = account();
        Instant first = caseFor(account, UUID.randomUUID());

        day(first, 6, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OPEN);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        day(first, 7, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OVERDUE);
        assertThat(standing(account)).isEqualTo(Standing.OVERDUE);
        day(first, 9, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OVERDUE);
        day(first, 10, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
        day(first, 29, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.BLOCKED);
        day(first, 30, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.WRITTEN_OFF);
        assertThat(standing(account)).as("decision 4").isEqualTo(Standing.BLOCKED);
        assertThat(theCase(account).retries()).isEqualTo(2);
    }

    /** Carried point 2: every timer due at once, after a stop of 31 days. They end where the rule says, in any order. */
    @Test
    void timersThatRunLateEndWhereTheirRulesSay() {
        UUID account = account();
        Instant first = caseFor(account, UUID.randomUUID());

        day(first, 31, account);

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.WRITTEN_OFF);
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
        assertThat(jdbc.queryForList("SELECT step FROM collections.case_step WHERE case_id = ? ORDER BY step",
                String.class, theCase(account).caseId())).containsExactly("BLOCKED", "OVERDUE", "WRITTEN_OFF");
    }

    /**
     * Carried point 2: the day-10 timer blocks a case that is still OPEN; it does not need OVERDUE first. The OVERDUE
     * timer is gone, as for a case whose timers were lost; the day-10 timer then finds OPEN.
     */
    @Test
    void theDayTenTimerBlocksACaseThatIsStillOpen() {
        UUID account = account();
        Instant first = caseFor(account, UUID.randomUUID());
        UUID caseId = theCase(account).caseId();
        jdbc.update("DELETE FROM collections.timer_queue WHERE queue_key = ?", caseId + ":OVERDUE");

        day(first, 10, account);

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
    }

    /**
     * Carried point 1 (review of #78): a timer never acts on the case's memory alone. Here the case holds an invoice
     * that is paid, as if its {@code InvoicePaid} were lost; on day 10 the timer asks Invoicing, finds it paid, and
     * closes the case. The standing is never BLOCKED.
     */
    @Test
    void aTimerAsksInvoicingAndClosesACaseWhoseInvoiceIsPaid() {
        InvoiceView invoice = declinedRenewal("DECLINE_HARD(100)");
        UUID account = invoice.accountId();
        awaitAttempt(invoice.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        Instant first = theCase(account).openedAt();
        FakeProvider.mode("NORMAL");
        invoicing.requestPaymentAgain(invoice.id(), 1);
        await().atMost(Duration.ofSeconds(15)).until(() -> theCase(account).status() == CaseStatus.CLOSED);
        // As if InvoicePaid had never reached Collections.
        jdbc.update("UPDATE collections.collection_case SET status = 'OPEN', closed_at = NULL WHERE account_id = ?",
                account);
        jdbc.update("UPDATE collections.case_invoice SET paid_at = NULL WHERE invoice_id = ?", invoice.id());
        assertThat(invoicing.invoicesFor(account).getLast().status()).isEqualTo(InvoiceStatus.PAID);

        day(first, 10, account);

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(theCase(account).unpaidInvoiceIds()).isEmpty();
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?"
                + " AND event_type LIKE '%StandingChanged'", Long.class, "%" + account + "%"))
                .as("the standing never changed").isZero();
    }

    /** Carried point 3: a decline joins a case that is OVERDUE or BLOCKED, and changes neither state. */
    @Test
    void aDeclineJoinsACaseThatIsOverdueOrBlocked() {
        UUID account = account();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        Instant opened = caseFor(account, first);

        day(opened, 8, account);
        publish(declined(account, second));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OVERDUE);
        day(opened, 11, account);
        publish(declined(account, third));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM collections.collection_case WHERE account_id = ?",
                Long.class, account)).isEqualTo(1);
        assertThat(theCase(account)).satisfies(c -> {
            assertThat(c.status()).isEqualTo(CaseStatus.BLOCKED);
            assertThat(c.unpaidInvoiceIds()).containsExactly(first, second, third);
        });
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
    }

    /** Carried point 3: InvoicePaid finds a case that is OVERDUE or BLOCKED; the standing returns to GOOD with it. */
    @Test
    void paidWhileBlockedClosesTheCaseAndTheStandingIsGood() {
        UUID account = account();
        UUID invoice = UUID.randomUUID();
        Instant first = caseFor(account, invoice);
        day(first, 11, account);
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);

        publish(paid(account, invoice));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        day(first, 31, account);
        assertThat(theCase(account).status()).as("a closed case's timers do nothing").isEqualTo(CaseStatus.CLOSED);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
    }

    /**
     * Carried point 3 and decision 4: the write-off ends the subscription, and the standing stays BLOCKED. Paid later,
     * the case closes and the standing is GOOD; the subscription stays ended.
     */
    @Test
    void theWriteOffEndsTheSubscriptionAndAPaymentLaterMakesTheStandingGood() {
        atTheStartOfALongMonth();
        InvoiceView invoice = declinedRenewal("DECLINE_HARD(100)");
        UUID account = invoice.accountId();
        awaitAttempt(invoice.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        Instant first = theCase(account).openedAt();

        day(first, 30, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.WRITTEN_OFF);
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
        assertThat(jdbc.queryForObject("SELECT status || ' ' || end_reason FROM subscription.subscription"
                + " WHERE account_id = ?", String.class, account)).isEqualTo("ENDED WRITE_OFF");

        FakeProvider.mode("NORMAL");
        invoicing.requestPaymentAgain(invoice.id(), 3);
        await().atMost(Duration.ofSeconds(15)).until(() -> theCase(account).status() == CaseStatus.CLOSED);
        await().atMost(Duration.ofSeconds(15)).until(() -> standing(account) == Standing.GOOD);
        assertThat(jdbc.queryForObject("SELECT status FROM subscription.subscription WHERE account_id = ?",
                String.class, account)).isEqualTo("ENDED");
    }

    /**
     * Rule 5's example: {@code InvoicePaid} and the day-10 timer at the same time. One waits for the other on the case's
     * lock, and reads the state the first left. The end state is never "case closed, standing BLOCKED". The test holds
     * the case's row lock until both wait for it.
     */
    @Test
    void aPaymentAndTheDayTenTimerAtTheSameTimeEndClosedAndGood() throws Exception {
        UUID account = account();
        UUID invoice = UUID.randomUUID();
        Instant first = caseFor(account, invoice);
        day(first, 9, account);
        UUID caseId = theCase(account).caseId();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM collections.collection_case WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, caseId);
                lock.executeQuery();
            }
            clock.set(first.plus(Duration.ofDays(10)).plus(Duration.ofHours(2)));
            publish(paid(account, invoice));
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
    }
}
