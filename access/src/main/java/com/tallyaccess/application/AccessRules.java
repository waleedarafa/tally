package com.tallyaccess.application;

import com.tallyaccess.application.AccessAnswer.Reason;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who may watch (design.md §8; Access rules 2 and 4). A value Access does not know never takes access away (ADR-033;
 * D13, trade-off T3): an unknown standing is not BLOCKED, and a source in an unknown state counts as active.
 */
public final class AccessRules {

    /** The values of version 1 (contracts/access-facts/v1/schema.json); later versions add values (ADR-016). */
    public static final Set<String> STANDINGS = Set.of("GOOD", "OVERDUE", "BLOCKED");
    public static final Set<String> KINDS = Set.of("SUBSCRIPTION");
    public static final Set<String> STATES = Set.of("ACTIVE");

    private AccessRules() {
    }

    public static AccessAnswer answer(UUID subscriberId, Optional<StoredFacts> stored) {
        if (stored.isEmpty()) {
            return new AccessAnswer(subscriberId, false, Reason.NOT_KNOWN, null, null);
        }
        StoredFacts facts = stored.get();
        if ("BLOCKED".equals(facts.standing())) {
            return answer(subscriberId, false, Reason.ACCOUNT_BLOCKED, facts);
        }
        // Version 1 has no source state that is not active; a state of a later version counts as active.
        boolean anActiveSource = !facts.sources().isEmpty();
        return anActiveSource
                ? answer(subscriberId, true, Reason.ACTIVE_SOURCE, facts)
                : answer(subscriberId, false, Reason.NO_ACTIVE_SOURCE, facts);
    }

    private static AccessAnswer answer(UUID subscriberId, boolean canWatch, Reason reason, StoredFacts facts) {
        return new AccessAnswer(subscriberId, canWatch, reason, facts.producedAt(), facts.sequence());
    }
}
