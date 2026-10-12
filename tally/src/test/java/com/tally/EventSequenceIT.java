package com.tally;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.shared.DomainEvent;
import com.tally.subscription.SubscriptionChanged;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-016, design.md §5: every event carries its aggregate's version as a sequence number, the version
 * the change it reports committed: Subscription, Invoice, and Payment (the collection request). Every
 * event published is recorded as it is published.
 */
@Import(EventSequenceIT.Recorder.class)
@TestPropertySource(properties = "tally.test.context=event-sequence")
class EventSequenceIT extends AbstractReconcilingTest {

    static final Queue<DomainEvent> PUBLISHED = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class Recorder {

        @EventListener
        void on(DomainEvent event) {
            PUBLISHED.add(event);
        }
    }

    <T extends DomainEvent> T awaitOne(Class<T> type, UUID account) {
        await().atMost(Duration.ofSeconds(15)).until(() -> !of(type, account).isEmpty());
        assertThat(of(type, account)).hasSize(1);
        return of(type, account).getFirst();
    }

    <T extends DomainEvent> List<T> of(Class<T> type, UUID account) {
        return PUBLISHED.stream().filter(type::isInstance).map(type::cast)
                .filter(e -> e.accountId().equals(account)).toList();
    }

    long invoiceVersion(UUID invoiceId) {
        return jdbc.queryForObject("SELECT version FROM invoicing.invoice WHERE id = ?", Long.class, invoiceId);
    }

    long requestVersion(String reference) {
        return jdbc.queryForObject("SELECT version FROM payment.collection_request WHERE reference = ?", Long.class,
                reference);
    }

    @Test
    void eachEventOfAPaidSignUpCarriesItsAggregatesVersion() {
        UUID account = subscribedAccount();

        SubscriptionChanged started = awaitOne(SubscriptionChanged.class, account);
        assertThat(started.sequence()).isEqualTo((long) started.version()).isEqualTo(1L);

        InvoiceIssued issued = awaitOne(InvoiceIssued.class, account);
        assertThat(issued.sequence()).as("issued").isEqualTo(1L);

        PaymentSucceeded succeeded = awaitOne(PaymentSucceeded.class, account);
        InvoicePaid paid = awaitOne(InvoicePaid.class, account);
        assertThat(succeeded.sequence()).as("a request made, then succeeded").isEqualTo(2L)
                .isEqualTo(requestVersion(succeeded.reference()));
        assertThat(paid.sequence()).as("an invoice issued, then paid").isEqualTo(2L)
                .isEqualTo(invoiceVersion(paid.invoiceId()));
    }

    /** The sequences the stored publications of {@code type} for {@code reference} carry, in order. */
    List<Long> storedSequences(Class<?> type, String reference) {
        return jdbc.queryForList("SELECT DISTINCT (serialized_event::jsonb ->> 'sequence')::bigint FROM event_publication"
                + " WHERE event_type = ? AND serialized_event LIKE ? ORDER BY 1", Long.class, type.getName(),
                "%" + reference + "%");
    }

    /**
     * A lying provider: a decline that reconciliation corrects to a charge (FR-018). The correction is a
     * later change of the same Payment, so it carries a higher sequence. Read from the stored
     * publications: any test context's reconciliation may publish the correction.
     */
    @Test
    void aCorrectedDeclineCarriesAHigherSequenceThanTheDecline() {
        FakeProvider.mode("LIE");
        UUID account = subscribedAccount();
        PaymentFailed failed = awaitOne(PaymentFailed.class, account);
        FakeProvider.mode("NORMAL");

        clock.advance(Duration.ofMinutes(5));
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !storedSequences(PaymentSucceeded.class, failed.reference()).isEmpty());

        assertThat(failed.sequence()).isEqualTo(2L);
        assertThat(storedSequences(PaymentFailed.class, failed.reference())).containsExactly(2L);
        assertThat(storedSequences(PaymentSucceeded.class, failed.reference())).containsExactly(3L)
                .containsExactly(requestVersion(failed.reference()));
    }

    @Test
    void aDeclineCarriesThePaymentsVersion() {
        FakeProvider.mode("DECLINE_HARD(100)");
        UUID account = subscribedAccount();

        PaymentFailed failed = awaitOne(PaymentFailed.class, account);
        assertThat(failed.sequence()).as("a request made, then declined").isEqualTo(2L)
                .isEqualTo(requestVersion(failed.reference()));
    }
}
