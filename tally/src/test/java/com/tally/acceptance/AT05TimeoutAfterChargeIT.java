package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AT-5. Timeout after charge (D3). Pass when: the attempt is UNKNOWN and not retried; it is
 * reconciled to SUCCEEDED; the invoice is PAID; the provider's charge count is 1.
 */
class AT05TimeoutAfterChargeIT extends AbstractReconcilingTest {

    @Test
    void aChargeThatTimedOutIsReconciledNotRetried() {
        double correctedBefore = corrected("UNKNOWN", "SUCCEEDED");
        // The provider charges the card, then answers after Tally's 5 s read timeout.
        FakeProvider.mode("TIMEOUT_AFTER_CHARGE");
        UUID account = subscribedAccount();
        InvoiceView invoice = firstInvoice(account);
        String reference = invoice.paymentReference();

        // Attempt UNKNOWN; the invoice waits for the truth (FR-018).
        awaitAttempt(reference, AttemptState.UNKNOWN, Duration.ofSeconds(15));
        FakeProvider.mode("NORMAL");
        assertThat(invoicing.invoicesFor(account).getFirst().status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);

        // Not retried: one request reaches the provider, however long we wait.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> FakeProvider.requestCount(reference) == 1);

        // Reconciliation's first check, 5 minutes later, finds the charge.
        clock.advance(Duration.ofMinutes(5));
        awaitAttempt(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> invoicing.invoicesFor(account).getFirst().status() == InvoiceStatus.PAID);

        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        assertThat(FakeProvider.requestCount(reference)).isEqualTo(1);
        assertThat(corrected("UNKNOWN", "SUCCEEDED")).isEqualTo(correctedBefore + 1);
    }
}
