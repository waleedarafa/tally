package com.tallyaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Partner bundles in Access's own database (ADR-056). */
public interface BundleStore {

    /**
     * Stores the fact unless the stored row for its partner and bundle has this partner sequence or a higher one;
     * one atomic statement; returns whether it was stored.
     */
    boolean applyIfNewer(BundleFact fact, Instant appliedAt);

    /** The facts for one subscriber, ordered by partner_id, bundle_id. */
    List<BundleFact> findBySubscriber(UUID subscriberId);
}
