package com.tallyaccess.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tallyaccess.application.AccessAnswer.Reason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The answers of design.md §8, and a value Access does not know never takes access away (ADR-033). */
class AccessRulesTest {

    static final UUID ID = UUID.randomUUID();
    static final Instant AT = Instant.parse("2026-11-13T06:00:00Z");

    static Optional<StoredFacts> facts(String standing, String... states) {
        return Optional.of(new StoredFacts(ID, 7, AT, standing, java.util.Arrays.stream(states)
                .map(s -> new AccessFactsMessage.Source("SUBSCRIPTION", UUID.randomUUID(), s)).toList()));
    }

    @Test
    void anActiveSourceAndAStandingNotBlockedMayWatch() {
        assertThat(AccessRules.answer(ID, facts("GOOD", "ACTIVE")))
                .isEqualTo(new AccessAnswer(ID, true, Reason.ACTIVE_SOURCE, AT, 7L));
        assertThat(AccessRules.answer(ID, facts("OVERDUE", "ACTIVE")).canWatch()).isTrue();
    }

    @Test
    void aBlockedAccountMayNotWatch() {
        assertThat(AccessRules.answer(ID, facts("BLOCKED", "ACTIVE")))
                .isEqualTo(new AccessAnswer(ID, false, Reason.ACCOUNT_BLOCKED, AT, 7L));
    }

    @Test
    void noActiveSourceMayNotWatch() {
        assertThat(AccessRules.answer(ID, facts("GOOD")))
                .isEqualTo(new AccessAnswer(ID, false, Reason.NO_ACTIVE_SOURCE, AT, 7L));
    }

    @Test
    void anAccountAccessDoesNotKnowIsNotKnownWithNoTimeAndNoSequence() {
        assertThat(AccessRules.answer(ID, Optional.empty()))
                .isEqualTo(new AccessAnswer(ID, false, Reason.NOT_KNOWN, null, null));
    }

    @Test
    void aStandingOrAStateAccessDoesNotKnowNeverTakesAccessAway() {
        assertThat(AccessRules.answer(ID, facts("SUSPENDED", "ACTIVE")).canWatch()).as("unknown standing").isTrue();
        assertThat(AccessRules.answer(ID, facts("GOOD", "PAUSED")).canWatch()).as("unknown state").isTrue();
        assertThat(AccessRules.answer(ID, Optional.of(new StoredFacts(ID, 7, AT, "GOOD", List.of(
                new AccessFactsMessage.Source("PARTNER_BUNDLE", UUID.randomUUID(), "ACTIVE")))))
                .canWatch()).as("unknown kind").isTrue();
    }
}
