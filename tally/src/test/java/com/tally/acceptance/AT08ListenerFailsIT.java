package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * AT-8. Listener fails (D2, ADR-022). Pass when: the failed publication is resubmitted within the
 * configured limit, and its effect happens exactly once.
 *
 * <p>The failure is a real one: the account has no payment token yet, so Invoicing's InvoiceIssued
 * listener cannot request collection and throws. The configured limit is the resubmission age
 * (5 minutes) plus one job interval (1 minute).
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=at08"
})
class AT08ListenerFailsIT extends AbstractProviderIntegrationTest {

    static final Duration LIMIT = Duration.ofMinutes(6);

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    /** The registry and the job row are shared by every test context: start from a known state. */
    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM event_publication WHERE completion_date IS NULL");
        jdbc.update("UPDATE platform.jobs SET status = 'READY', claim_token = NULL, due_at = ?, last_success_at = NULL"
                + " WHERE queue_key = 'event-resubmission'", clock.instant().atOffset(ZoneOffset.UTC));
    }

    String publicationStatus(String reference) {
        return jdbc.queryForObject("SELECT status FROM event_publication WHERE serialized_event LIKE ?"
                + " AND event_type LIKE '%InvoiceIssued' AND listener_id LIKE '%InvoicingListeners%'",
                String.class, "%" + reference + "%");
    }

    int attempts(String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Integer.class, reference);
    }

    Instant jobLastSuccess() {
        OffsetDateTime at = jdbc.queryForObject(
                "SELECT last_success_at FROM platform.jobs WHERE queue_key = 'event-resubmission'", OffsetDateTime.class);
        return at == null ? Instant.EPOCH : at.toInstant();
    }

    @Test
    void aFailedListenerIsResubmittedWithinTheLimitAndItsEffectHappensOnce() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        subscriptions.subscribe(account, "BASIC");
        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicing.invoicesFor(account).isEmpty());
        String reference = invoicing.invoicesFor(account).getFirst().paymentReference();

        // The listener failed: its publication is FAILED, and nothing was requested.
        await().atMost(Duration.ofSeconds(10)).until(() -> "FAILED".equals(publicationStatus(reference)));
        assertThat(attempts(reference)).isZero();
        Instant failedAt = clock.instant();

        // The cause goes away: the customer's payment token arrives.
        accounts.recordPaymentToken(account, FakeProvider.token());

        // Time passes a minute at a time; each minute, the resubmission job runs once.
        while (invoicing.invoicesFor(account).getFirst().status() != InvoiceStatus.PAID
                && Duration.between(failedAt, clock.instant()).compareTo(LIMIT.plusMinutes(5)) < 0) {
            clock.advance(Duration.ofMinutes(1));
            Instant now = clock.instant();
            await().atMost(Duration.ofSeconds(10)).until(() -> !jobLastSuccess().isBefore(now));
            // The job marks a publication RESUBMITTED, then its listener runs on the listener pool. Wait for
            // the listener to settle it (COMPLETED, or FAILED again) before reading its effects: otherwise
            // "no attempt yet" passes before the listener has run, and the clock moves on a minute early.
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> List.of("FAILED", "COMPLETED").contains(publicationStatus(reference)));
            // Let any collection the resubmission started finish before time moves on.
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));
            await().atMost(Duration.ofSeconds(5)).until(() -> attempts(reference) == 0
                    || invoicing.invoicesFor(account).getFirst().status() == InvoiceStatus.PAID);
        }
        Duration taken = Duration.between(failedAt, clock.instant());

        InvoiceView invoice = invoicing.invoicesFor(account).getFirst();
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(taken).isLessThanOrEqualTo(LIMIT);
        assertThat(publicationStatus(reference)).isEqualTo("COMPLETED");

        // Exactly once in effect, also after more resubmission runs.
        clock.advance(Duration.ofMinutes(10));
        Instant later = clock.instant();
        await().atMost(Duration.ofSeconds(10)).until(() -> !jobLastSuccess().isBefore(later));
        assertThat(attempts(reference)).isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        System.out.printf("AT-8: resubmitted and paid %d min after the listener failed (limit %d min)%n",
                taken.toMinutes(), LIMIT.toMinutes());
    }
}
