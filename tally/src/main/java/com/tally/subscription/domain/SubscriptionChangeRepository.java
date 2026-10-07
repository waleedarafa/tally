package com.tally.subscription.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionChangeRepository extends JpaRepository<SubscriptionChange, UUID> {

    List<SubscriptionChange> findBySubscriptionIdOrderByVersion(UUID subscriptionId);
}
