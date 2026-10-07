package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AT-6. Lying provider (D3). Pass when: reconciliation corrects the outcome, and
 * {@code tally.payment.outcome_corrected} increments.
 *
 * <p>The direction tested is the one the brief's reconciliation covers: the provider charged the card
 * but answered "declined". The other direction (answered "succeeded" for a declined charge) is a
 * Spike B question for build step 8: reconciliation does not re-check SUCCEEDED attempts.
 */
class AT06LyingProviderIT extends AbstractReconcilingTest {

    @Test
    void aChargeReportedAsDeclinedIsCorrectedAndTheInvoicePaid() {
        double correctedBefore = corrected("FAILED", "SUCCEEDED");
        FakeProvider.mode("LIE");
        UUID account = subscribedAccount();
        InvoiceView invoice = firstInvoice(account);
        String reference = invoice.paymentReference();

        // Tally believes the answer: FAILED (HARD), invoice PAYMENT_FAILED.
        awaitAttempt(reference, AttemptState.FAILED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");
        assertThat(payments.status(reference).orElseThrow().failureReason()).contains(FailureReason.HARD);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> invoicing.invoicesFor(account).getFirst().status() == InvoiceStatus.PAYMENT_FAILED);

        // Reconciliation re-checks recent failures and finds the charge.
        clock.advance(Duration.ofMinutes(5));
        awaitAttempt(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> invoicing.invoicesFor(account).getFirst().status() == InvoiceStatus.PAID);

        assertThat(corrected("FAILED", "SUCCEEDED")).isEqualTo(correctedBefore + 1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }
}
