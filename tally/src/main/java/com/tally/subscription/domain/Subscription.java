package com.tally.subscription.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A subscription and its periods (design.md §2; Design Record §12: Subscription owns periods). Refers to
 * the account and offer by identifier only. S1: one current period, the latest. S2: periods never overlap
 * or leave gaps, by construction: the next starts where the last ended. S5: periods follow the account's
 * billing day, the local day the subscription started ({@link BillingCalendar}).
 */
@Entity
@Table(schema = "subscription", name = "subscription")
public class Subscription {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    /** The offer version signed up to; renewals keep it (decision A, O3). */
    @Column(name = "offer_version", nullable = false)
    private int offerVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "anchor_day", nullable = false)
    private int anchorDay;

    /** Every period so far, oldest first; unique per (subscription, number) in the database. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "subscription", name = "subscription_period",
            joinColumns = @JoinColumn(name = "subscription_id"))
    @OrderColumn(name = "position")
    private List<SubscriptionPeriod> periods = new ArrayList<>();

    /**
     * The aggregate's version, carried by every SubscriptionChanged. An optimistic lock: of two
     * renewals of the same period at the same time, one commits and the other fails.
     */
    @Version
    @Column(nullable = false)
    private int version;

    protected Subscription() {
    }

    /** Starts on the local day of {@code now} in the account's time zone, with period 1. */
    public static Subscription start(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone) {
        Subscription s = new Subscription();
        s.id = UUID.randomUUID();
        s.accountId = accountId;
        s.planId = planId;
        s.offerVersion = offerVersion;
        s.startedAt = now;
        LocalDate startedOn = LocalDate.ofInstant(now, zone);
        s.anchorDay = BillingCalendar.startingOn(startedOn).anchorDay();
        s.periods.add(new SubscriptionPeriod(1, startedOn, s.calendar().nextBillingDate(startedOn)));
        s.version = 1;
        return s;
    }

    /**
     * Renews into period {@code number}: the period after the current one. Returns false and changes
     * nothing if that period already exists, so a repeated renewal is harmless (ADR-003).
     *
     * @throws IllegalStateException if {@code number} skips a period
     */
    public boolean renew(int number) {
        SubscriptionPeriod current = currentPeriod();
        if (number <= current.number()) {
            return false;
        }
        if (number != current.number() + 1) {
            throw new IllegalStateException("Subscription " + id + " is in period " + current.number()
                    + "; it cannot renew into period " + number);
        }
        periods.add(new SubscriptionPeriod(number, current.end(), calendar().nextBillingDate(current.end())));
        return true;
    }

    public SubscriptionPeriod currentPeriod() {
        return periods.getLast();
    }

    public List<SubscriptionPeriod> periods() {
        return List.copyOf(periods);
    }

    public BillingCalendar calendar() {
        return new BillingCalendar(anchorDay);
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

    public int offerVersion() {
        return offerVersion;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public int version() {
        return version;
    }
}
