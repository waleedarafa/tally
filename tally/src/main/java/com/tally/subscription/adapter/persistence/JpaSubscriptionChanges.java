package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.SubscriptionChange;
import com.tally.subscription.domain.SubscriptionChanges;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implements {@link SubscriptionChanges}. */
interface JpaSubscriptionChanges extends JpaRepository<SubscriptionChange, UUID>, SubscriptionChanges {
}
