package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.domain.Invoice;
import com.tally.payment.AttemptState;
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
 * Design.md §4 (PR #13 review): Invoicing publishes InvoicePaymentDeclined when a payment for one of its
 * invoices is declined, with the ADR-016 envelope; and {@code requestPaymentAgain(invoiceId, retry)} asks
 * Payment to collect the invoice's reference again, for Collections.
 */
@Import(PaymentDeclinedIT.Recorder.class)
@TestPropertySource(properties = "tally.test.context=payment-declined")
class PaymentDeclinedIT extends AbstractReconcilingTest {

    static final Queue<InvoicePaymentDeclined> DECLINED = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class Recorder {

        @EventListener
        void on(InvoicePaymentDeclined event) {
            DECLINED.add(event);
        }
    }

    InvoiceView declinedInvoice(UUID account) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAYMENT_FAILED));
        FakeProvider.mode("NORMAL");
        return invoicing.invoicesFor(account).getFirst();
    }

    @Test
    void aDeclinedPaymentPublishesInvoicePaymentDeclinedWithTheInvoicesVersion() {
        FakeProvider.mode("DECLINE_HARD(100)");
        UUID account = subscribedAccount();
        InvoiceView invoice = declinedInvoice(account);

        await().atMost(Duration.ofSeconds(5)).until(() -> DECLINED.stream().anyMatch(e -> e.accountId().equals(account)));
        List<InvoicePaymentDeclined> events = DECLINED.stream().filter(e -> e.accountId().equals(account)).toList();
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.invoiceId()).isEqualTo(invoice.id());
            assertThat(e.paymentReference()).isEqualTo(invoice.paymentReference());
            assertThat(e.amount()).isEqualTo(invoice.amount());
            assertThat(e.sequence()).as("issued, then declined").isEqualTo(Invoice.ISSUED_VERSION + 1)
                    .isEqualTo(jdbc.queryForObject("SELECT version FROM invoicing.invoice WHERE id = ?", Long.class,
                            invoice.id()));
        });
    }

    @Test
    void aPaymentRequestedAgainCollectsTheInvoice() {
        FakeProvider.mode("DECLINE_HARD(100)");
        UUID account = subscribedAccount();
        InvoiceView invoice = declinedInvoice(account);

        invoicing.requestPaymentAgain(invoice.id(), 1);

        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoicing.invoicesFor(account).getFirst().status() == InvoiceStatus.PAID);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Long.class, invoice.paymentReference())).isEqualTo(2);
    }

    @Test
    void aPaidInvoiceIsNotCollectedAgain() {
        UUID account = subscribedAccount();
        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoicing.invoicesFor(account).getFirst().status() == InvoiceStatus.PAID);
        InvoiceView invoice = invoicing.invoicesFor(account).getFirst();

        invoicing.requestPaymentAgain(invoice.id(), 1);

        assertThat(payments.status(invoice.paymentReference()).orElseThrow().state())
                .isEqualTo(AttemptState.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Long.class, invoice.paymentReference())).isEqualTo(1);
    }
}
