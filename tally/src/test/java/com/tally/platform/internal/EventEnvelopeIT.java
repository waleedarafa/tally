package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.shared.DomainEvent;
import com.tally.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-016, rule 7: the sequence is an added field. An event stored by a release before Slice 1R PR-7,
 * with no sequence, still reads, with a null sequence; one stored with it reads it back.
 */
class EventEnvelopeIT extends AbstractIntegrationTest {

    @Autowired
    JsonMapper json;

    @Test
    void anEventStoredWithoutASequenceStillReads() {
        Instant at = Instant.parse("2026-01-15T10:00:00Z");
        UUID account = UUID.randomUUID();
        Money amount = Money.of(999, "USD");
        List<DomainEvent> events = List.of(
                new InvoiceIssued(UUID.randomUUID(), at, account, UUID.randomUUID(), "INV-1", amount, 7L),
                new InvoicePaid(UUID.randomUUID(), at, account, UUID.randomUUID(), "INV-1", 7L),
                new PaymentSucceeded(UUID.randomUUID(), at, account, "INV-1", amount, "ch_1", 7L),
                new PaymentFailed(UUID.randomUUID(), at, account, "INV-1", amount, FailureReason.HARD, 7L));

        for (DomainEvent event : events) {
            String stored = json.writeValueAsString(event);
            assertThat(stored).contains("\"sequence\":7");
            assertThat(json.readValue(stored, event.getClass()).sequence()).isEqualTo(7L);

            String beforePr7 = stored.replace(",\"sequence\":7", "");
            assertThat(beforePr7).doesNotContain("sequence");
            DomainEvent read = json.readValue(beforePr7, event.getClass());
            assertThat(read.sequence()).as(event.getClass().getSimpleName()).isNull();
            assertThat(read.eventId()).isEqualTo(event.eventId());
        }
    }
}
