package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.SubscriptionChange;
import com.tally.subscription.domain.SubscriptionChanges;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implements {@link SubscriptionChanges}; its method names stay here (CLAUDE.md rule 14). */
interface JpaSubscriptionChanges extends JpaRepository<SubscriptionChange, UUID>, SubscriptionChanges {

    @Override
    default void add(SubscriptionChange change) {
        save(change);
    }

    @Override
    default List<SubscriptionChange> historyOf(UUID subscriptionId) {
        return findBySubscriptionIdOrderByVersion(subscriptionId);
    }

    List<SubscriptionChange> findBySubscriptionIdOrderByVersion(UUID subscriptionId);
}
