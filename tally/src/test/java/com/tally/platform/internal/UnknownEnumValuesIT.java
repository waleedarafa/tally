package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.catalog.BillingPeriod;
import com.tally.invoicing.InvoiceStatus;
import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import tools.jackson.databind.json.JsonMapper;

/**
 * CLAUDE.md rule 7: during a rolling deployment, this version can read an event a newer one stored,
 * holding an enum value it does not know (slice 1 review part 3: D15 will add a failure reason).
 * The value reads as UNKNOWN, and the listener completes instead of failing.
 */
class UnknownEnumValuesIT extends AbstractIntegrationTest {

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IncompleteEventPublications incomplete;

    @Test
    void unknownValuesOfEveryCrossModuleEnumReadAsUnknown() {
        String unknown = "\"A_VALUE_FROM_A_NEWER_RELEASE\"";
        assertThat(json.readValue(unknown, BillingPeriod.class)).isEqualTo(BillingPeriod.UNKNOWN);
        assertThat(json.readValue(unknown, InvoiceStatus.class)).isEqualTo(InvoiceStatus.UNKNOWN);
        assertThat(json.readValue(unknown, AttemptState.class)).isEqualTo(AttemptState.UNKNOWN);
        assertThat(json.readValue(unknown, FailureReason.class)).isEqualTo(FailureReason.UNKNOWN);
        assertThat(json.readValue(unknown, SubscriptionChangeType.class)).isEqualTo(SubscriptionChangeType.UNKNOWN);
    }

    @Test
    void storedEventsWithUnknownValuesAreDeliveredWithoutFailing() {
        Instant earlier = clock.instant().minus(Duration.ofHours(1));
        UUID account = UUID.randomUUID();

        // A payment failure with a reason a newer release added, for an invoice awaiting payment.
        String reference = "INV-" + UUID.randomUUID();
        jdbc.update("INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor,"
                        + " currency, period_start, period_end, payment_reference, issued_at)"
                        + " VALUES (?, ?, ?, 'BASIC', 'PAYMENT_PENDING', 999, 'USD', ?, ?, ?, ?)",
                UUID.randomUUID(), account, UUID.randomUUID(), LocalDate.parse("2026-01-15"),
                LocalDate.parse("2026-02-15"), reference, earlier.atOffset(ZoneOffset.UTC));
        String failed = json.writeValueAsString(new PaymentFailed(UUID.randomUUID(), earlier, account, reference,
                Money.of(999, "USD"), FailureReason.HARD)).replace("\"HARD\"", "\"AUTHENTICATION_REQUIRED\"");
        UUID failedPublication = store(PaymentFailed.class, failed,
                "com.tally.invoicing.application.InvoicingListeners.on(com.tally.payment.PaymentFailed)", earlier);

        // A subscription change of a kind a newer release added.
        String changed = json.writeValueAsString(new SubscriptionChanged(UUID.randomUUID(), earlier, account,
                UUID.randomUUID(), "BASIC", 2, SubscriptionChangeType.STARTED)).replace("\"STARTED\"", "\"PAUSED\"");
        UUID invoicingPublication = store(SubscriptionChanged.class, changed,
                "com.tally.invoicing.application.InvoicingListeners.on(com.tally.subscription.SubscriptionChanged)",
                earlier);
        UUID entitlementPublication = store(SubscriptionChanged.class, changed,
                "com.tally.entitlement.application.EntitlementService.on(com.tally.subscription.SubscriptionChanged)",
                earlier);

        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT id FROM event_publication WHERE completion_date IS NOT NULL AND id IN (?, ?, ?)", UUID.class,
                failedPublication, invoicingPublication, entitlementPublication)).hasSize(3));
        // An unknown failure reason is unresolved: the invoice still waits (ADR-018).
        assertThat(jdbc.queryForObject("SELECT status FROM invoicing.invoice WHERE payment_reference = ?",
                String.class, reference)).isEqualTo("PAYMENT_PENDING");
        // An unknown change type grants nothing and invoices nothing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entitlement.access WHERE account_id = ?", Long.class,
                account)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.invoice WHERE account_id = ?", Long.class,
                account)).isEqualTo(1);
    }

    /** A publication as a newer release would have left it: stored, not yet completed. */
    private UUID store(Class<?> type, String serialized, String listener, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " status, completion_attempts) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', 1)",
                id, listener, type.getName(), serialized, publishedAt.atOffset(ZoneOffset.UTC));
        return id;
    }
}
