package com.tally.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.payment.AttemptState;
import com.tally.shared.DomainEvent;
import com.tally.shared.Money;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slice 3 PR-3 (brief rule 7; design.md §5, proposed in ADR-037): one case covers every unpaid invoice of the
 * account. A decline while a case is open adds the invoice to the case; the retry asks for each unpaid invoice; the
 * case closes only when none is unpaid.
 *
 * <p>An account has one subscription, so the real flow makes a second unpaid invoice only a month after the first.
 * Where a test needs two at once, it publishes the second decline itself, as Invoicing would, for an invoice that
 * exists only in the event.
 */
@TestPropertySource(properties = "tally.test.context=case-covers-every-invoice")
class CaseCoversEveryInvoiceIT extends AbstractReconcilingTest {

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

    /** Until no publication to Collections' listeners naming the account is still in flight; none may have failed. */
    void awaitCollections(UUID account) {
        String inFlight = "SELECT count(*) FROM event_publication WHERE listener_id LIKE 'com.tally.collections.%'"
                + " AND serialized_event LIKE ? AND completion_date IS NULL";
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                inFlight + " AND status IS DISTINCT FROM 'FAILED'", Long.class, "%" + account + "%") == 0);
        assertThat(jdbc.queryForObject(inFlight + " AND status = 'FAILED'", Long.class, "%" + account + "%"))
                .as("failed Collections publications").isZero();
    }

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    long cases(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM collections.collection_case WHERE account_id = ?",
                Long.class, account);
    }

    /** "status: unpaid invoices", for every case of the account, oldest first. */
    List<String> casesAndTheirUnpaidInvoices(UUID account) {
        return jdbc.queryForList("""
                SELECT c.status || ': ' || coalesce((SELECT string_agg(i.invoice_id::text, ',' ORDER BY i.invoice_id)
                    FROM collections.case_invoice i WHERE i.case_id = c.id AND i.paid_at IS NULL), '-')
                FROM collections.collection_case c WHERE c.account_id = ? ORDER BY c.opened_at, c.status
                """, String.class, account);
    }

    @Test
    void aDeclineWhileACaseIsOpenJoinsTheCase() {
        UUID account = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        publish(declined(account, first));
        awaitCollections(account);
        publish(declined(account, second));
        awaitCollections(account);
        publish(declined(account, second));
        awaitCollections(account);

        assertThat(cases(account)).isEqualTo(1);
        assertThat(collections.caseFor(account).orElseThrow()).satisfies(c -> {
            assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
            assertThat(c.invoiceIds()).as("in the order they joined; a repeat adds nothing")
                    .containsExactly(first, second);
            assertThat(c.unpaidInvoiceIds()).containsExactlyInAnyOrder(first, second);
        });
    }

    /**
     * The brief's test: two declines for one account at the same time give one case with two invoices (CLAUDE.md
     * rule 7). The test locks the case table until both listeners wait for it, so neither can finish first.
     */
    @Test
    void twoDeclinesAtTheSameTimeGiveOneCaseWithTwoInvoices() throws Exception {
        UUID account = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("LOCK TABLE collections.collection_case IN SHARE MODE");
            publish(declined(account, first));
            publish(declined(account, second));
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }
        awaitCollections(account);

        assertThat(cases(account)).as("one case").isEqualTo(1);
        assertThat(collections.caseFor(account).orElseThrow().unpaidInvoiceIds())
                .as("with both invoices").containsExactlyInAnyOrder(first, second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collections.retry_queue WHERE payload IN"
                + " (SELECT id::text FROM collections.collection_case WHERE account_id = ?)", Long.class, account))
                .as("one day-3 retry, for the case").isEqualTo(1);
    }

    @Test
    void theCaseClosesOnlyWhenNoInvoiceIsUnpaid() {
        UUID account = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        publish(declined(account, first));
        awaitCollections(account);
        publish(declined(account, second));
        awaitCollections(account);

        publish(paid(account, first));
        awaitCollections(account);
        assertThat(collections.caseFor(account).orElseThrow()).satisfies(c -> {
            assertThat(c.status()).as("an invoice is still unpaid").isEqualTo(CaseStatus.OPEN);
            assertThat(c.unpaidInvoiceIds()).containsExactly(second);
        });

        publish(paid(account, second));
        awaitCollections(account);
        assertThat(collections.caseFor(account).orElseThrow()).satisfies(c -> {
            assertThat(c.status()).isEqualTo(CaseStatus.CLOSED);
            assertThat(c.closedAt()).isEqualTo(clock.instant());
            assertThat(c.unpaidInvoiceIds()).isEmpty();
        });
    }

    /**
     * {@code InvoicePaid} and a decline of another invoice at the same time: one waits for the other on the case's
     * lock. Either the case closes and the decline opens a new one, or the decline joins first and the case stays
     * open. Never a closed case with an unpaid invoice.
     */
    @Test
    void aPaymentAndAnotherDeclineAtTheSameTimeNeverLeaveAClosedCaseWithAnUnpaidInvoice() throws Exception {
        UUID account = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        publish(declined(account, first));
        awaitCollections(account);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("LOCK TABLE collections.collection_case IN SHARE MODE");
            publish(paid(account, first));
            publish(declined(account, second));
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }
        awaitCollections(account);

        assertThat(casesAndTheirUnpaidInvoices(account)).as("either order, the second invoice in an open case")
                .isIn(List.of("OPEN: " + second), List.of("CLOSED: -", "OPEN: " + second));
    }

    /** The retry asks for each unpaid invoice of the case, with the case's retry number. */
    @Test
    void theRetryAsksForEveryUnpaidInvoice() {
        FakeProvider.mode("DECLINE_HARD(100)");
        UUID account = subscribedAccount();
        InvoiceView first = firstInvoice(account);
        awaitAttempt(first.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        UUID second = UUID.randomUUID();
        publish(declined(account, second));
        awaitCollections(account);

        clock.advance(Duration.ofDays(3).plusMinutes(1));

        verify(invoicingSpy, timeout(15_000)).requestPaymentAgain(first.id(), 1);
        verify(invoicingSpy, timeout(15_000)).requestPaymentAgain(second, 1);
        assertThat(collections.caseFor(account).orElseThrow().retries()).isEqualTo(1);
    }

    /**
     * A decline that arrives after its invoice was paid (a publication resubmitted late) opens no case: the case
     * covers unpaid invoices only, and Invoicing says this one is paid.
     */
    @Test
    void aDeclineOfAnInvoiceAlreadyPaidOpensNoCase() {
        UUID account = subscribedAccount();
        await().atMost(Duration.ofSeconds(15)).until(() -> firstInvoice(account).status() == InvoiceStatus.PAID);

        publish(declined(account, firstInvoice(account).id()));
        awaitCollections(account);

        assertThat(cases(account)).isZero();
    }
}
