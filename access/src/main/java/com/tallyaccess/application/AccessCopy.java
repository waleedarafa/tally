package com.tallyaccess.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access's copy of who may watch: applies what the log brings, and answers from what it holds (ADR-001, ADR-012).
 *
 * <p>Metrics: {@code tally.access.values.not_known} counts each schema version, standing, source kind or source
 * state a message carries that version 1 does not know, by {@code field}; {@code tally.access.unreadable} counts messages Access
 * cannot store (no account, sequence or time, or not JSON); {@code tally.access.last_applied.age_seconds} is the age
 * of the newest facts Access holds (their {@code producedAt}); 0 while it holds none.
 */
@Service
public class AccessCopy {

    private static final Logger log = LoggerFactory.getLogger(AccessCopy.class);

    /** One message read from the log, at its partition and offset. */
    public record Read(int partition, long offset, AccessFactsMessage message) {
    }

    private final FactsStore store;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Counter unreadable;

    AccessCopy(FactsStore store, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.clock = clock;
        this.meters = meters;
        this.unreadable = Counter.builder("tally.access.unreadable").register(meters);
        Gauge.builder("tally.access.last_applied.age_seconds", this, c -> c.lastAppliedAge().toMillis() / 1000.0)
                .baseUnit("seconds").strongReference(true).register(meters);
    }

    public AccessAnswer answer(UUID subscriberId) {
        return AccessRules.answer(subscriberId, store.find(subscriberId));
    }

    /**
     * Applies what one poll of the log brought, and the positions the consumer reached, in one transaction (ADR-035):
     * the facts and the position cannot disagree. A message is applied only if its sequence is higher than the one
     * stored, so a repeated or older message changes nothing (CLAUDE.md rule 15). {@code null} is a message that could
     * not be read; it is counted and passed over.
     */
    @Transactional
    public void apply(String topic, List<Read> reads, Map<Integer, Long> positions) {
        for (Read read : reads) {
            AccessFactsMessage message = read.message();
            if (message == null || !message.complete()) {
                unreadable.increment();
                log.warn("Message at {}-{}:{} cannot be stored; passed over", topic, read.partition(), read.offset());
                continue;
            }
            countValuesNotKnown(message);
            store.applyIfNewer(message, clock.instant());
        }
        positions.forEach((partition, next) -> store.advance(topic, partition, next));
    }

    private void countValuesNotKnown(AccessFactsMessage message) {
        // A later version is read as version 1 (add only, ADR-016); counted, so a mistake shows.
        if (message.schemaVersion() == null || message.schemaVersion() != 1) {
            notKnown("schemaVersion");
        }
        if (message.standing() == null || !AccessRules.STANDINGS.contains(message.standing())) {
            notKnown("standing");
        }
        for (AccessFactsMessage.Source source : message.sourcesOrNone()) {
            if (source.kind() == null || !AccessRules.KINDS.contains(source.kind())) {
                notKnown("sources.kind");
            }
            if (source.state() == null || !AccessRules.STATES.contains(source.state())) {
                notKnown("sources.state");
            }
        }
    }

    private void notKnown(String field) {
        Counter.builder("tally.access.values.not_known").tag("field", field).register(meters).increment();
    }

    public Map<Integer, Long> positions(String topic) {
        return store.positions(topic);
    }

    /** Whether this copy once reached the end of the log an instance saw at start (ADR-036). */
    public boolean completeOnce() {
        return store.completeOnce();
    }

    /** Marks this copy as complete once; from then on an instance that starts answers from it at once. */
    public void markComplete() {
        store.markComplete(clock.instant());
    }

    Duration lastAppliedAge() {
        return store.newestProducedAt().map(produced -> Duration.between(produced, clock.instant()))
                .filter(age -> !age.isNegative()).orElse(Duration.ZERO);
    }
}
