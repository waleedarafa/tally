package com.tally.subscription.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    boolean existsByAccountId(UUID accountId);
}
