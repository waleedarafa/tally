package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.collections.CaseStatus;
import com.tally.collections.CaseView;
import com.tally.collections.Collections;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AT-16. Declined payment (ADR-005, design.md §5; Slice 1R builds OPEN, the day-3 retry and close). Pass
 * when: one Collections case opens; on day 3 a new attempt is made for the same reference, with its own key;
 * the payment closes the case. Extended (PR-6 review, proposed ADR-026): a decline the provider reported
 * for a charge that went through, followed by the day-3 retry, charges once and closes the case.
 *
 * <p>The decline is made with the fake in a decline mode, which is reset as soon as the attempt is
 * declined: other test contexts, on later clocks, may run the day-3 timer early.
 */
class AT16DeclinedPaymentIT extends AbstractReconcilingTest {

    @Autowired
    Collections collections;

    CaseView awaitCase(UUID account, CaseStatus status) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                collections.caseFor(account).map(c -> c.status() == status).orElse(false));
        return collections.caseFor(account).orElseThrow();
    }

    InvoiceView declinedFirstInvoice(String mode) {
        FakeProvider.mode(mode);
        UUID account = subscribedAccount();
        InvoiceView invoice = firstInvoice(account);
        awaitAttempt(invoice.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        FakeProvider.mode("NORMAL");
        return invoice;
    }

    List<String> attempts(String reference) {
        return jdbc.queryForList("SELECT idempotency_key || '=' || state FROM payment.payment_attempt"
                + " WHERE reference = ? ORDER BY number", String.class, reference);
    }

    long cases(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM collections.collection_case WHERE account_id = ?",
                Long.class, account);
    }

    void dayThree() {
        clock.advance(Duration.ofDays(3).plusMinutes(1));
    }

    @Test
    void aDeclineOpensOneCaseTheDayThreeRetryCollectsAndThePaymentClosesIt() {
        InvoiceView invoice = declinedFirstInvoice("DECLINE_HARD(100)");
        UUID account = invoice.accountId();
        CaseView opened = awaitCase(account, CaseStatus.OPEN);
        assertThat(opened.invoiceId()).isEqualTo(invoice.id());
        assertThat(attempts(invoice.paymentReference())).hasSize(1);

        dayThree();

        CaseView closed = awaitCase(account, CaseStatus.CLOSED);
        System.out.println("AT-16 declined: " + attempts(invoice.paymentReference()) + ", case " + closed);
        assertThat(invoicing.invoicesFor(account).getFirst().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(attempts(invoice.paymentReference())).as("a new attempt, same reference, its own key")
                .containsExactly(invoice.paymentReference() + ":1=DECLINED", invoice.paymentReference() + ":2=SUCCEEDED");
        assertThat(cases(account)).as("one case").isEqualTo(1);
        assertThat(closed.retries()).isEqualTo(1);
    }

    /**
     * The provider charged but reported a decline (LIE). The case opens on the decline. On day 3 Payment
     * looks the reference up before retrying, finds the charge, corrects the decline and makes no new
     * attempt (proposed ADR-026); the invoice is paid and the case closes. Reconciliation's own 48-hour
     * re-check of the decline may find the charge first: either way, one charge and the case closed.
     */
    @Test
    void aLyingDeclineFollowedByTheDayThreeRetryChargesOnceAndClosesTheCase() {
        InvoiceView invoice = declinedFirstInvoice("LIE");
        UUID account = invoice.accountId();
        awaitCase(account, CaseStatus.OPEN);

        dayThree();

        awaitCase(account, CaseStatus.CLOSED);
        System.out.println("AT-16 lying decline: " + attempts(invoice.paymentReference()) + ", charges "
                + FakeProvider.chargeCount(invoice.paymentReference()));
        assertThat(invoicing.invoicesFor(account).getFirst().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(attempts(invoice.paymentReference())).as("no new attempt")
                .containsExactly(invoice.paymentReference() + ":1=SUCCEEDED");
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).as("charged once").isEqualTo(1);
        assertThat(cases(account)).isEqualTo(1);
    }
}
