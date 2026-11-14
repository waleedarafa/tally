package com.tally.accessfeed.application;

import com.tally.accessfeed.domain.AccountFactsStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The feed's backlog (Slice 2 PR-5, CLAUDE.md rule 8): {@code tally.accessfeed.unsent.oldest_age_seconds}, the age
 * of the oldest facts not yet in the event log; 0 when the log has them all. Read from the database when scraped, so
 * it keeps ageing when no relay runs. The relay's queue reports its own health as every queue does.
 */
@Component
class AccessFeedHealth {

    private final AccountFactsStore facts;
    private final Clock clock;

    AccessFeedHealth(AccountFactsStore facts, MeterRegistry meters, Clock clock) {
        this.facts = facts;
        this.clock = clock;
        Gauge.builder("tally.accessfeed.unsent.oldest_age_seconds", this, h -> h.oldestUnsentAge().toMillis() / 1000.0)
                .baseUnit("seconds").strongReference(true).register(meters);
    }

    Duration oldestUnsentAge() {
        return facts.oldestUnsent()
                .map(produced -> Duration.between(produced, clock.instant()))
                .filter(age -> !age.isNegative())
                .orElse(Duration.ZERO);
    }
}
