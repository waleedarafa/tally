package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.payment.PaymentStatus;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import com.tally.support.SharedJob;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spike B (brief section 9): does reconciliation find the truth? TIMEOUT_AFTER_CHARGE and LIE, with
 * the true outcome both ways. Time is moved in one-minute steps, waiting each step for the job to
 * run, so the time to resolution is measured on the business clock. Prints evidence with SPIKE-B and
 * asserts what was observed. Findings: docs/briefs/slice-1-spikes.md.
 */
class SpikeBReconciliationIT extends AbstractReconcilingTest {

    static final Duration LIMIT = Duration.ofHours(50);

    Instant jobLastSuccess() {
        OffsetDateTime at = jdbc.queryForObject(
                "SELECT last_success_at FROM payment.jobs WHERE queue_key = 'payment-reconciliation'", OffsetDateTime.class);
        return at == null ? Instant.EPOCH : at.toInstant();
    }

    /** Moves the clock a minute at a time until the attempt leaves {@code from}; returns the business time taken. */
    Duration timeToLeave(String reference, AttemptState from) {
        Instant start = clock.instant();
        Duration step = Duration.ofMinutes(1);
        while (payments.status(reference).orElseThrow().state() == from) {
            if (Duration.between(start, clock.instant()).compareTo(LIMIT) > 0) {
                return null;
            }
            // Big jumps once nothing can change before the next daily check.
            if (Duration.between(start, clock.instant()).compareTo(Duration.ofHours(2)) > 0) {
                step = Duration.ofHours(1);
            }
            clock.advance(step);
            Instant now = clock.instant();
            SharedJob.RECONCILIATION.await(jdbc, clock, Duration.ofSeconds(10), () -> !jobLastSuccess().isBefore(now));
        }
        return Duration.between(start, clock.instant());
    }

    record Result(AttemptState state, Duration taken, int charges) {
    }

    Result run(String scenario, String truth, String answer, AttemptState believed, String... modes) {
        FakeProvider.mode(modes);
        UUID account = subscribedAccount();
        InvoiceView invoice = firstInvoice(account);
        String reference = invoice.paymentReference();
        long wallStart = System.nanoTime();
        awaitAttempt(reference, believed, Duration.ofSeconds(15));
        FakeProvider.mode("NORMAL");
        Duration taken = timeToLeave(reference, believed);
        PaymentStatus status = payments.status(reference).orElseThrow();
        System.out.printf("SPIKE-B %s | provider truth: %s | provider answered: %s | Tally believed: %s | "
                        + "after reconciliation: %s%s | business time to resolution: %s | provider charges: %d | "
                        + "invoice: %s | wall time: %d s%n",
                scenario, truth, answer, believed, status.state(),
                status.failureReason().map(r -> " (" + r + ")").orElse(""),
                taken == null ? "NOT RESOLVED within " + LIMIT.toHours() + " h" : taken.toMinutes() + " min",
                FakeProvider.chargeCount(reference), invoicing.invoicesFor(account).getFirst().status(),
                Duration.ofNanos(System.nanoTime() - wallStart).toSeconds());
        return new Result(status.state(), taken, FakeProvider.chargeCount(reference));
    }

    @Test
    void b1TimeoutAfterASuccessfulCharge() {
        Result r = run("B1 TIMEOUT_AFTER_CHARGE", "SUCCEEDED", "(no answer: read timeout)", AttemptState.UNKNOWN,
                "TIMEOUT_AFTER_CHARGE");
        assertThat(r.state()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(r.taken()).isLessThanOrEqualTo(Duration.ofHours(1)); // D3: UNKNOWN resolved within 1 hour
        assertThat(r.charges()).isEqualTo(1);
    }

    @Test
    void b2TimeoutAfterADeclinedCharge() {
        Result r = run("B2 TIMEOUT_AFTER_CHARGE + DECLINE_HARD(100)", "DECLINED_HARD", "(no answer: read timeout)",
                AttemptState.UNKNOWN, "TIMEOUT_AFTER_CHARGE", "DECLINE_HARD(100)");
        assertThat(r.state()).isEqualTo(AttemptState.DECLINED);
        assertThat(r.taken()).isLessThanOrEqualTo(Duration.ofHours(1));
    }

    @Test
    void b3LieAboutASuccessfulCharge() {
        Result r = run("B3 LIE", "SUCCEEDED", "DECLINED_HARD", AttemptState.DECLINED, "LIE");
        assertThat(r.state()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(r.taken()).isLessThanOrEqualTo(Duration.ofHours(1));
    }

    @Test
    void b4LieAboutADeclinedCharge() {
        Result r = run("B4 LIE + DECLINE_HARD(100)", "DECLINED_HARD", "SUCCEEDED", AttemptState.SUCCEEDED,
                "LIE", "DECLINE_HARD(100)");
        // PINS A KNOWN GAP (Spike B finding): reconciliation never re-checks SUCCEEDED attempts, so a
        // success reported for a declined charge is never corrected. Change this when that is decided.
        assertThat(r.state()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(r.taken()).isNull();
    }
}
