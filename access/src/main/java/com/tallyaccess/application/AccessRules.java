package com.tallyaccess.application;

import com.tallyaccess.application.AccessAnswer.Reason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who may watch (design.md §8; Access rules 2 and 4). A value Access does not know never takes access away (ADR-033;
 * D13, trade-off T3): an unknown standing is not BLOCKED, and a source in an unknown state counts as active. An active
 * partner bundle gives access whatever the standing (Access rules 2 and 5, ADR-056).
 */
public final class AccessRules {

    /** The values of version 1 (contracts/access-facts/v1/schema.json); later versions add values (ADR-016). */
    public static final Set<String> STANDINGS = Set.of("GOOD", "OVERDUE", "BLOCKED");
    public static final Set<String> KINDS = Set.of("SUBSCRIPTION");
    public static final Set<String> STATES = Set.of("ACTIVE");

    private AccessRules() {
    }

    public static AccessAnswer answer(UUID subscriberId, Optional<StoredFacts> stored) {
        return answer(subscriberId, stored, List.of());
    }

    public static AccessAnswer answer(UUID subscriberId, Optional<StoredFacts> stored, List<BundleFact> bundles) {
        List<BundleFact> active = bundles.stream().filter(b -> b.state() == BundleFact.State.ACTIVE).toList();
        if (!active.isEmpty()) {
            if (stored.isPresent()) {
                return answer(subscriberId, true, Reason.ACTIVE_SOURCE, stored.get());
            }
            return new AccessAnswer(subscriberId, true, Reason.ACTIVE_SOURCE,
                    active.stream().map(BundleFact::occurredAt).max(Instant::compareTo).orElse(null), null);
        }
        if (stored.isEmpty() && !bundles.isEmpty()) {
            return new AccessAnswer(subscriberId, false, Reason.NO_ACTIVE_SOURCE,
                    bundles.stream().map(BundleFact::occurredAt).max(Instant::compareTo).orElse(null), null);
        }
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
