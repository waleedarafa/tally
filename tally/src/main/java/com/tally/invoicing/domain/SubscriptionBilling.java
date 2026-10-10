package com.tally.invoicing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Invoicing's own record of a subscription, built from Subscription's events: its anticorruption
 * layer (FR-014). Holds the anchor day of the billing calendar (FR-002).
 */
@Entity
@Table(schema = "invoicing", name = "subscription_billing")
public class SubscriptionBilling {

    @Id
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "anchor_day", nullable = false)
    private int anchorDay;

    @Column(name = "started_on", nullable = false)
    private LocalDate startedOn;

    protected SubscriptionBilling() {
    }

    public SubscriptionBilling(UUID subscriptionId, UUID accountId, String planId, LocalDate startedOn) {
        this.subscriptionId = subscriptionId;
        this.accountId = accountId;
        this.planId = planId;
        this.startedOn = startedOn;
        this.anchorDay = BillingCalendar.startingOn(startedOn).anchorDay();
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }

    public UUID accountId() {
        return accountId;
    }

    public String planId() {
        return planId;
    }

    public LocalDate startedOn() {
        return startedOn;
    }

    public BillingCalendar calendar() {
        return new BillingCalendar(anchorDay);
    }
}
