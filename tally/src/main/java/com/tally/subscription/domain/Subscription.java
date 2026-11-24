package com.tally.subscription.domain;

import com.tally.subscription.EndReason;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A subscription and its periods (design.md §2; Design Record §12: Subscription owns periods). Refers to
 * the account and offer by identifier only. S1: one current period, the latest. S2: periods never overlap
 * or leave gaps, by construction: the next starts where the last ended. S5: periods follow the account's
 * billing day (ADR-043, {@link BillingCalendar}). A copy is stored as {@code anchorDay}; Account is the
 * source of truth. A start that is not on that day has a short first period.
 */
@Entity
@Table(schema = "subscription", name = "subscription")
public class Subscription {

    /** What can happen to a subscription (design.md §5). Every state change goes through {@link #next}. */
    public enum Event {
        /** The trial ends (design.md §5: TRIAL → ACTIVE). */
        TRIAL_ENDS,
        /** The household asks to cancel (design.md §5: TRIAL or ACTIVE → CANCELLING). */
        CANCEL_REQUESTED,
        /** A renewal for the current period (design.md §5: ACTIVE → ACTIVE). */
        RENEW,
        /**
         * A cancel requested before period end but processed after renewal: the next period is reversed
         * (design.md §5: ACTIVE → ENDED).
         */
        LATE_CANCEL_REQUESTED,
        /** The current period ends (design.md §5: CANCELLING → ENDED). */
        PERIOD_ENDS,
        /**
         * Collections ends the subscription from any live state; EndReason says whether that was a write-off or a
         * declined first invoice (design.md §5).
         */
        FORCE_END
    }

    /** The transition table (design.md §5). Anything absent is refused. */
    static final Map<SubscriptionStatus, Map<Event, SubscriptionStatus>> TRANSITIONS = new EnumMap<>(SubscriptionStatus.class);

    static {
        on(SubscriptionStatus.TRIAL, Event.TRIAL_ENDS, SubscriptionStatus.ACTIVE);
        on(SubscriptionStatus.TRIAL, Event.CANCEL_REQUESTED, SubscriptionStatus.CANCELLING);
        on(SubscriptionStatus.TRIAL, Event.FORCE_END, SubscriptionStatus.ENDED);
        on(SubscriptionStatus.ACTIVE, Event.CANCEL_REQUESTED, SubscriptionStatus.CANCELLING);
        on(SubscriptionStatus.ACTIVE, Event.RENEW, SubscriptionStatus.ACTIVE);
        on(SubscriptionStatus.ACTIVE, Event.LATE_CANCEL_REQUESTED, SubscriptionStatus.ENDED);
        on(SubscriptionStatus.ACTIVE, Event.FORCE_END, SubscriptionStatus.ENDED);
        on(SubscriptionStatus.CANCELLING, Event.PERIOD_ENDS, SubscriptionStatus.ENDED);
        on(SubscriptionStatus.CANCELLING, Event.FORCE_END, SubscriptionStatus.ENDED);
    }

    private static void on(SubscriptionStatus from, Event event, SubscriptionStatus to) {
        TRANSITIONS.computeIfAbsent(from, f -> new EnumMap<>(Event.class)).put(event, to);
    }

    /** The state {@code event} moves {@code from} to, or empty if the table refuses it (ADR-018). */
    public static Optional<SubscriptionStatus> next(SubscriptionStatus from, Event event) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(from, Map.of()).get(event));
    }

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason")
    private EndReason endReason;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected Subscription() {
    }

    /**
     * The subscription id of an HTTP sign-up: a function of the account and the Idempotency-Key, so a
     * retry or takeover of the same key addresses the same row (ADR-045).
     */
    public static UUID idOfSignUp(UUID accountId, UUID signUpKey) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(signUpKey, "signUpKey");
        return UUID.nameUUIDFromBytes(("subscription-sign-up:" + accountId + ":" + signUpKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Starts on the local day of {@code now} in the account's time zone, with period 1. */
    public static Subscription start(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone) {
        return start(accountId, planId, offerVersion, now, zone, billingDayOf(now, zone), null);
    }

    /**
     * Starts as {@link #start}, with the id determined by the account and {@code signUpKey} (ADR-045).
     */
    public static Subscription start(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone,
            UUID signUpKey) {
        return start(accountId, planId, offerVersion, now, zone, billingDayOf(now, zone), signUpKey);
    }

    /**
     * Starts as {@link #start}, against the account's billing day (ADR-043). The first period is short
     * when {@code now} is not on that day.
     */
    public static Subscription start(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone,
            int billingDay) {
        return newSubscription(accountId, planId, offerVersion, now, zone, billingDay, null);
    }

    /**
     * Starts as {@link #start} against the account's billing day, with the id determined by the account
     * and {@code signUpKey} (ADR-043, ADR-045).
     */
    public static Subscription start(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone,
            int billingDay, UUID signUpKey) {
        return newSubscription(accountId, planId, offerVersion, now, zone, billingDay, signUpKey);
    }

    /**
     * Starts on the local day of {@code now}, with period 1, in TRIAL (design.md §5): the trial ends into ACTIVE.
     * Same dates and version as {@link #start}; only the status differs.
     */
    public static Subscription startTrial(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone) {
        return startTrial(accountId, planId, offerVersion, now, zone, billingDayOf(now, zone), null);
    }

    /**
     * Starts as {@link #startTrial}, with the id determined by the account and {@code signUpKey} (ADR-045).
     */
    public static Subscription startTrial(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone,
            UUID signUpKey) {
        return startTrial(accountId, planId, offerVersion, now, zone, billingDayOf(now, zone), signUpKey);
    }

    /**
     * Starts as {@link #startTrial}, against the account's billing day (ADR-043).
     */
    public static Subscription startTrial(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone,
            int billingDay) {
        Subscription s = newSubscription(accountId, planId, offerVersion, now, zone, billingDay, null);
        s.status = SubscriptionStatus.TRIAL;
        return s;
    }

    /**
     * Starts as {@link #startTrial} against the account's billing day, with the id determined by the
     * account and {@code signUpKey} (ADR-043, ADR-045).
     */
    public static Subscription startTrial(UUID accountId, String planId, int offerVersion, Instant now, ZoneId zone,
            int billingDay, UUID signUpKey) {
        Subscription s = newSubscription(accountId, planId, offerVersion, now, zone, billingDay, signUpKey);
        s.status = SubscriptionStatus.TRIAL;
        return s;
    }

    private static int billingDayOf(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone).getDayOfMonth();
    }

    private static Subscription newSubscription(UUID accountId, String planId, int offerVersion, Instant now,
            ZoneId zone, int billingDay, UUID signUpKey) {
        Subscription s = new Subscription();
        s.id = signUpKey != null ? idOfSignUp(accountId, signUpKey) : UUID.randomUUID();
        s.accountId = accountId;
        s.planId = planId;
        s.offerVersion = offerVersion;
        s.startedAt = now;
        LocalDate startedOn = LocalDate.ofInstant(now, zone);
        s.anchorDay = new BillingCalendar(billingDay).anchorDay();
        s.periods.add(new SubscriptionPeriod(1, startedOn, FirstPeriod.endDate(startedOn, billingDay)));
        s.version = 1;
        return s;
    }

    /**
     * Trial conversion: TRIAL → ACTIVE (design.md §5). Returns false and changes nothing if the subscription is not
     * in TRIAL.
     */
    public boolean endTrial() {
        if (status != SubscriptionStatus.TRIAL) {
            return false;
        }
        change(Event.TRIAL_ENDS);
        return true;
    }

    /**
     * A cancel requested: TRIAL or ACTIVE → CANCELLING (design.md §5). Returns false and changes nothing otherwise.
     */
    public boolean cancel() {
        if (status != SubscriptionStatus.TRIAL && status != SubscriptionStatus.ACTIVE) {
            return false;
        }
        change(Event.CANCEL_REQUESTED);
        return true;
    }

    /**
     * The current period ends while cancelling: CANCELLING → ENDED (design.md §5). The end date and the reason
     * (CANCELLED) are set so the household's access ends at the boundary of the paid period. Returns false and
     * changes nothing otherwise: ENDED refuses everything, an end included.
     */
    public boolean periodEnded(Instant now) {
        if (status != SubscriptionStatus.CANCELLING) {
            return false;
        }
        change(Event.PERIOD_ENDS);
        endReason = EndReason.CANCELLED;
        endedAt = now;
        return true;
    }

    /**
     * Renews into period {@code number}: the period after the current one. Returns false and changes
     * nothing if that period already exists, so a repeated renewal is harmless (ADR-003), or if the subscription is
     * not ACTIVE: renew(n) is for a live ACTIVE subscription only (design.md §5).
     *
     * @throws IllegalStateException if {@code number} skips a period
     */
    public boolean renew(int number) {
        if (status != SubscriptionStatus.ACTIVE) {
            // ENDED refuses everything; a trial or cancelling subscription is not renewed either (design.md §5).
            return false;
        }
        SubscriptionPeriod current = currentPeriod();
        if (number <= current.number()) {
            return false;
        }
        if (number != current.number() + 1) {
            throw new IllegalStateException("Subscription " + id + " is in period " + current.number()
                    + "; it cannot renew into period " + number);
        }
        change(Event.RENEW);
        periods.add(new SubscriptionPeriod(number, current.end(), calendar().nextBillingDate(current.end())));
        return true;
    }

    /**
     * Ends the subscription (design.md §5: any live state to ENDED). Returns false and changes nothing if it has
     * already ended: ENDED refuses everything, an end included, and keeps the first reason.
     *
     * @throws IllegalArgumentException if the reason is not known
     */
    public boolean end(EndReason reason, Instant now) {
        if (reason == null || reason == EndReason.UNKNOWN) {
            throw new IllegalArgumentException("An end needs a known reason: " + reason);
        }
        if (status == SubscriptionStatus.ENDED) {
            return false;
        }
        change(Event.FORCE_END);
        endReason = reason;
        endedAt = now;
        return true;
    }

    /** Moves to the state {@code event} allows, or throws (ADR-018). Nothing changes on a refusal. */
    private void change(Event event) {
        status = next(status, event).orElseThrow(() -> new IllegalStateException(
                "Subscription " + id + " is " + status + ": " + event + " is not allowed (ADR-018)"));
    }

    /**
     * Changes the plan this ACTIVE subscription is on (WAR-62, ADR-041). Returns false and changes
     * nothing if it is not ACTIVE, or if it is already on that plan and version.
     */
    public boolean changePlan(String planId, int offerVersion) {
        Objects.requireNonNull(planId, "planId");
        if (offerVersion < 1) {
            throw new IllegalArgumentException("Offer versions are numbered from 1: " + offerVersion);
        }
        if (status != SubscriptionStatus.ACTIVE) {
            return false;
        }
        if (this.planId.equals(planId) && this.offerVersion == offerVersion) {
            return false;
        }
        this.planId = planId;
        this.offerVersion = offerVersion;
        return true;
    }

    public SubscriptionStatus status() {
        return status;
    }

    /** Why it ended; null while it is live. */
    public EndReason endReason() {
        return endReason;
    }

    /** When it ended; null while it is live. */
    public Instant endedAt() {
        return endedAt;
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

    public int anchorDay() {
        return anchorDay;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public int version() {
        return version;
    }
}
