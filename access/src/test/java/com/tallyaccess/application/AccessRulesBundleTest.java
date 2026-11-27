package com.tallyaccess.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tallyaccess.application.AccessAnswer.Reason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessRulesBundleTest {

    static final UUID ID = UUID.randomUUID();
    static final Instant AT = Instant.parse("2026-11-13T06:00:00Z");
    static final Instant B = Instant.parse("2026-11-20T10:00:00Z");

    static Optional<StoredFacts> facts(String standing, String... states) {
        return Optional.of(new StoredFacts(ID, 7, AT, standing, java.util.Arrays.stream(states)
                .map(s -> new AccessFactsMessage.Source("SUBSCRIPTION", UUID.randomUUID(), s)).toList()));
    }

    static BundleFact bundle(BundleFact.State state, Instant at) {
        return new BundleFact("etisalat", "B-1", ID, state, 1, at);
    }

    @Test
    void aBundleAloneMayWatch() {
        assertThat(AccessRules.answer(ID, Optional.empty(), List.of(bundle(BundleFact.State.ACTIVE, B))))
                .isEqualTo(new AccessAnswer(ID, true, Reason.ACTIVE_SOURCE, B, null));
    }

    @Test
    void aBundleWinsOverABlockedAccount() {
        assertThat(AccessRules.answer(ID, facts("BLOCKED", "ACTIVE"), List.of(bundle(BundleFact.State.ACTIVE, B))))
                .isEqualTo(new AccessAnswer(ID, true, Reason.ACTIVE_SOURCE, AT, 7L));
    }

    @Test
    void anEndedBundleDoesNotHelpABlockedAccount() {
        assertThat(AccessRules.answer(ID, facts("BLOCKED"), List.of(bundle(BundleFact.State.ENDED, B))))
                .isEqualTo(new AccessAnswer(ID, false, Reason.ACCOUNT_BLOCKED, AT, 7L));
    }

    @Test
    void onlyAnEndedBundleIsNoActiveSource() {
        assertThat(AccessRules.answer(ID, Optional.empty(), List.of(bundle(BundleFact.State.ENDED, B))))
                .isEqualTo(new AccessAnswer(ID, false, Reason.NO_ACTIVE_SOURCE, B, null));
    }

    @Test
    void noFactsAndNoBundlesIsNotKnown() {
        assertThat(AccessRules.answer(ID, Optional.empty(), List.of()))
                .isEqualTo(new AccessAnswer(ID, false, Reason.NOT_KNOWN, null, null));
    }

    @Test
    void aBundleGivesAccessWithNoSubscription() {
        assertThat(AccessRules.answer(ID, facts("GOOD"), List.of(bundle(BundleFact.State.ACTIVE, B))))
                .isEqualTo(new AccessAnswer(ID, true, Reason.ACTIVE_SOURCE, AT, 7L));
    }
}
