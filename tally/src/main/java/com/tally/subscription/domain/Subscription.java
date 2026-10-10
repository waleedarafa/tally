package com.tally.subscription.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A light domain model (FR-003). Refers to the account and plan by identifier only. */
@Entity
@Table(schema = "subscription", name = "subscription")
public class Subscription {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private int version;

    protected Subscription() {
    }

    public static Subscription start(UUID accountId, String planId, Instant now) {
        Subscription s = new Subscription();
        s.id = UUID.randomUUID();
        s.accountId = accountId;
        s.planId = planId;
        s.startedAt = now;
        s.version = 1;
        return s;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public String planId() {
        return planId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public int version() {
        return version;
    }
}
