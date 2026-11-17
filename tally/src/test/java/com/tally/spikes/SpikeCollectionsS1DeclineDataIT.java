package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.collections.CaseStatus;
import com.tally.collections.Collections;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Slice 3 spike S-1: how does Collections know that a declined invoice is the first invoice of a subscription, and
 * that a decline belongs to retry 2? Pinned here as it is on {@code main}, so a change to either event or to how
 * Invoicing publishes fails this test and is seen.
 *
 * <ol>
 *   <li>{@code InvoicePaymentDeclined} carries no subscription, no period and no retry number; {@code PaymentFailed}
 *       carries no attempt or retry number.</li>
 *   <li>A declined retry publishes a second {@code PaymentFailed} and <b>no</b> second {@code InvoicePaymentDeclined}:
 *       the invoice is already {@code PAYMENT_FAILED}, so {@code Invoice.paymentFailed()} changes nothing. Today
 *       Collections never learns that a retry was declined.</li>
 *   <li>"First invoice" can be found through {@code InvoicingApi} with no change: the declined invoice names its
 *       subscription, and it is the earliest period of that subscription's invoices.</li>
 * </ol>
 */
@Import(SpikeCollectionsS1DeclineDataIT.Recorder.class)
@TestPropertySource(properties = "tally.test.context=spike-collections-s1")
class SpikeCollectionsS1DeclineDataIT extends AbstractReconcilingTest {

    static final Queue<InvoicePaymentDeclined> DECLINED = new ConcurrentLinkedQueue<>();
    static final Queue<PaymentFailed> FAILED = new ConcurrentLinkedQueue<>();

    /** Listens after commit, so it sees only what a committed transaction published. */
    @TestConfiguration
    static class Recorder {

        @TransactionalEventListener
        void on(InvoicePaymentDeclined event) {
            DECLINED.add(event);
        }

        @TransactionalEventListener
        void on(PaymentFailed event) {
            FAILED.add(event);
        }
    }

    @Autowired
    Collections collections;

    @AfterEach
    void providerNormal() {
        FakeProvider.mode("NORMAL");
    }

    static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    List<InvoicePaymentDeclined> declinedFor(UUID account) {
        return DECLINED.stream().filter(e -> e.accountId().equals(account)).toList();
    }

    List<PaymentFailed> failedFor(UUID account) {
        return FAILED.stream().filter(e -> e.accountId().equals(account)).toList();
    }

    @Test
    void theEventsCarryNoSubscriptionNoPeriodAndNoRetryNumber() {
        List<String> declined = components(InvoicePaymentDeclined.class);
        List<String> failed = components(PaymentFailed.class);
        System.out.println("SPIKE-C-S1 InvoicePaymentDeclined: " + declined);
        System.out.println("SPIKE-C-S1 PaymentFailed: " + failed);

        assertThat(declined).containsExactly("eventId", "occurredAt", "accountId", "invoiceId", "paymentReference",
                "amount", "sequence");
        assertThat(failed).containsExactly("eventId", "occurredAt", "accountId", "reference", "amount", "reason",
                "sequence");
    }

    @Test
    void aDeclinedRetryPublishesNoSecondInvoicePaymentDeclinedAndTheFirstInvoiceIsFoundThroughInvoicingApi() {
        // The renewal's charge is declined, then the day-3 retry (since Slice 3 PR-7 a case starts from a renewal).
        InvoiceView invoice = declinedRenewal("DECLINE_HARD(100)");
        UUID account = invoice.accountId();
        awaitAttempt(invoice.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account)
                .map(c -> c.status() == CaseStatus.OPEN).orElse(false));

        clock.advance(Duration.ofDays(3).plusMinutes(1));
        await().atMost(Duration.ofSeconds(20)).until(() -> failedFor(account).size() == 2);
        // Give a second InvoicePaymentDeclined, if there were one, the time to arrive.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> declinedFor(account).size() == 1);

        List<String> attempts = jdbc.queryForList("SELECT number || '=' || state FROM payment.payment_attempt"
                + " WHERE reference = ? ORDER BY number", String.class, invoice.paymentReference());
        System.out.println("SPIKE-C-S1 attempts " + attempts + "; PaymentFailed " + failedFor(account).size()
                + " (" + failedFor(account).stream().map(PaymentFailed::reason).toList() + "); InvoicePaymentDeclined "
                + declinedFor(account).size() + "; invoice " + invoicing.invoicesFor(account).getLast().status());
        assertThat(attempts).containsExactly("1=DECLINED", "2=DECLINED");
        assertThat(failedFor(account)).extracting(PaymentFailed::reason)
                .allMatch(reason -> reason != FailureReason.UNKNOWN);
        assertThat(declinedFor(account)).as("the retry's decline reaches Collections").hasSize(1);
        assertThat(collections.caseFor(account).orElseThrow().retries()).isEqualTo(1);

        // "First invoice", through the API Collections already has: the earliest period of its subscription. Since
        // Slice 3 PR-7 a declined first invoice opens no case (025), so the case here is a renewal's: not the earliest.
        InvoiceView declined = invoicing.invoicesFor(account).stream()
                .filter(i -> i.id().equals(declinedFor(account).getFirst().invoiceId())).findFirst().orElseThrow();
        InvoiceView earliest = invoicing.invoicesForSubscription(declined.subscriptionId()).stream()
                .min(Comparator.comparing(InvoiceView::periodStart)).orElseThrow();
        assertThat(earliest.id()).as("the declined renewal is not its subscription's first").isNotEqualTo(declined.id());
        assertThat(declined.periodStart()).isEqualTo(earliest.periodEnd());
    }
}
