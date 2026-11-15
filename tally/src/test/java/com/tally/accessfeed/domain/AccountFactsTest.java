package com.tally.accessfeed.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountFactsTest {

    static final Instant NOW = Instant.parse("2026-11-15T20:00:00Z");

    /**
     * ENDED refuses everything (design.md §5), whatever the sequence: an event with none ("order not known", CLAUDE.md
     * rule 7) is applied to a live source, but never brings an ended one back (review 1 of Slice 3 PR-2).
     */
    @Test
    void anEndedSourceNeverChangesEvenForAnEventWithNoSequence() {
        AccountFacts facts = AccountFacts.none(UUID.randomUUID());
        UUID subscription = UUID.randomUUID();
        facts.applySource(SourceKind.SUBSCRIPTION, subscription, SourceState.ACTIVE, 1L, NOW);
        facts.applySource(SourceKind.SUBSCRIPTION, subscription, SourceState.ENDED, 5L, NOW);
        long ended = facts.sequence();

        facts.applySource(SourceKind.SUBSCRIPTION, subscription, SourceState.ACTIVE, null, NOW.plusSeconds(1));
        facts.applySource(SourceKind.SUBSCRIPTION, subscription, SourceState.ACTIVE, 6L, NOW.plusSeconds(2));

        assertThat(facts.sources()).singleElement()
                .isEqualTo(new Source(SourceKind.SUBSCRIPTION, subscription, SourceState.ENDED, 5L));
        assertThat(facts.sequence()).as("nothing sent changed").isEqualTo(ended);
        assertThat(facts.producedAt()).isEqualTo(NOW);
    }

    @Test
    void anEventWithNoSequenceStillAppliesToALiveSource() {
        AccountFacts facts = AccountFacts.none(UUID.randomUUID());
        UUID subscription = UUID.randomUUID();
        facts.applySource(SourceKind.SUBSCRIPTION, subscription, SourceState.ACTIVE, 3L, NOW);

        facts.applySource(SourceKind.SUBSCRIPTION, subscription, SourceState.ENDED, null, NOW.plusSeconds(1));

        assertThat(facts.sources()).extracting(Source::state).containsExactly(SourceState.ENDED);
        assertThat(facts.sequence()).isEqualTo(2);
    }
}
