package com.tally.invoicing.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionBillingRepository extends JpaRepository<SubscriptionBilling, UUID> {
}
