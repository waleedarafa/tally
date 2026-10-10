package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.Subscriptions;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implements {@link Subscriptions}. */
interface JpaSubscriptions extends JpaRepository<Subscription, UUID>, Subscriptions {
}
