package com.tally.subscription.domain;

import com.tally.subscription.SubscriptionChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One entry of a subscription's timeline. */
@Entity
@Table(schema = "subscription", name = "subscription_change")
public class SubscriptionChange {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionChangeType type;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SubscriptionChange() {
    }

    public SubscriptionChange(UUID subscriptionId, int version, SubscriptionChangeType type, String planId,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.subscriptionId = subscriptionId;
        this.version = version;
        this.type = type;
        this.planId = planId;
        this.occurredAt = occurredAt;
    }

    public int version() {
        return version;
    }

    public SubscriptionChangeType type() {
        return type;
    }

    public String planId() {
        return planId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
