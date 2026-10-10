package com.tally.payment.domain;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One attempt to collect an amount for a reference. Its lifecycle is an enum and a transition table
 * (ADR-018, design.md §5): every change goes through {@link #move}, which refuses any transition the
 * table does not hold. Payment allows a new attempt only while no attempt for the reference is
 * REQUESTED, SENT or UNKNOWN, and never after SUCCEEDED (P2; the partial unique index guards it).
 */
@Entity
@Table(schema = "payment", name = "payment_attempt")
public class PaymentAttempt {

    /** What can happen to an attempt. */
    public enum Event {
        /** The worker commits the attempt as sent, before calling the provider (FR-005). */
        SEND,
        CHARGED,
        DECLINED,
        /** The provider failed or refused before charging (a 503, a 429, unreachable). */
        PROVIDER_ERROR,
        /** The call timed out, or failed in a way that leaves the charge in doubt. */
        TIMED_OUT,
        /** Sent longer ago than the call timeout, with no result: its worker died (AT-15). */
        STALE,
        /** The provider's record shows a charge. */
        RESOLVED_CHARGED,
        /** The provider's record, or a repeat with the attempt's key, shows a decline. */
        RESOLVED_DECLINED
    }

    /** The transition table (design.md §5). Anything absent is refused. */
    static final Map<AttemptState, Map<Event, AttemptState>> TRANSITIONS = new EnumMap<>(AttemptState.class);

    static {
        on(AttemptState.REQUESTED, Event.SEND, AttemptState.SENT);
        on(AttemptState.SENT, Event.CHARGED, AttemptState.SUCCEEDED);
        on(AttemptState.SENT, Event.DECLINED, AttemptState.DECLINED);
        on(AttemptState.SENT, Event.PROVIDER_ERROR, AttemptState.TECHNICAL_FAILURE);
        on(AttemptState.SENT, Event.TIMED_OUT, AttemptState.UNKNOWN);
        on(AttemptState.SENT, Event.STALE, AttemptState.UNKNOWN);
        on(AttemptState.UNKNOWN, Event.RESOLVED_CHARGED, AttemptState.SUCCEEDED);
        on(AttemptState.UNKNOWN, Event.RESOLVED_DECLINED, AttemptState.DECLINED);
        // Kept from the first run (FR-018, AT-6): a provider can report a decline for a charge that went
        // through; the recorded truth corrects it. Not in design.md §5's table.
        on(AttemptState.DECLINED, Event.RESOLVED_CHARGED, AttemptState.SUCCEEDED);
    }

    private static void on(AttemptState from, Event event, AttemptState to) {
        TRANSITIONS.computeIfAbsent(from, f -> new EnumMap<>(Event.class)).put(event, to);
    }

    /** The state {@code event} moves {@code from} to, or empty if the table refuses it. */
    public static Optional<AttemptState> next(AttemptState from, Event event) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(from, Map.of()).get(event));
    }

    @Id
    private UUID id;

    /** The collection request this attempt belongs to (FR-041). Empty only for rows of an older release. */
    @Column(name = "request_id")
    private UUID requestId;

    @Column(nullable = false)
    private String reference;

    /** 1 for a request's first attempt; each retry adds one. Unique per reference. */
    @Column(nullable = false)
    private int number;

    /**
     * The provider key this attempt's charge is sent with: the reference plus the attempt number, so a
     * retry never reuses an earlier attempt's key, whose decline the provider would replay (CONTRACT.md
     * A22). Stored with the charge's parameters below, which a repeat by key must match exactly (A23).
     */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "payment_token", nullable = false)
    private String paymentToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private FailureReason failureReason;

    @Column(name = "charge_id")
    private String chargeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When the attempt was committed as sent: the start of its call (stale rule, ADR-018). */
    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "reconcile_at")
    private Instant reconcileAt;

    @Column(name = "reconcile_checks", nullable = false)
    private int reconcileChecks;

    protected PaymentAttempt() {
    }

    public static PaymentAttempt requested(UUID requestId, String reference, UUID accountId, Money amount,
            String paymentToken, Instant now) {
        PaymentAttempt a = new PaymentAttempt();
        a.id = UUID.randomUUID();
        a.requestId = requestId;
        a.reference = reference;
        a.accountId = accountId;
        a.amountMinor = amount.minorUnits();
        a.currency = amount.currency().getCurrencyCode();
        a.paymentToken = paymentToken;
        a.number = 1;
        a.idempotencyKey = keyOf(reference, a.number);
        a.state = AttemptState.REQUESTED;
        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    /** The next try for the same request, after this one failed technically. */
    public PaymentAttempt retry(Instant now) {
        if (state != AttemptState.TECHNICAL_FAILURE) {
            throw new IllegalStateException("Attempt " + id + " is " + state + ": only a technical failure is retried");
        }
        PaymentAttempt next = requested(requestId, reference, accountId, amount(), paymentToken, now);
        next.number = number + 1;
        next.idempotencyKey = keyOf(reference, next.number);
        return next;
    }

    static String keyOf(String reference, int number) {
        return reference + ":" + number;
    }

    /**
     * Commits the attempt as sent (REQUESTED to SENT). The worker claims through an atomic update with the
     * same effect ({@link PaymentAttempts#markSent}); this is the aggregate's form of that transition.
     */
    public void sent(Instant now) {
        move(Event.SEND, now);
        this.sentAt = now;
    }

    /**
     * Proposed ADR-026 (b) and (c): whether this attempt's charge may be repeated with its key, to learn its
     * outcome. Only while the key is younger than {@code window}, measured from when it was sent (from when
     * the attempt was made, for one with no send time): the provider's retention minus a margin. Past it,
     * the provider may have forgotten the key, and a repeat would charge a second time (CONTRACT.md A23).
     */
    public boolean mayRepeatKey(Duration window, Instant now) {
        Instant keyFirstUsed = sentAt != null ? sentAt : createdAt;
        return keyFirstUsed.plus(window).isAfter(now);
    }

    public void succeeded(String chargeId, Instant now) {
        move(Event.CHARGED, now);
        this.chargeId = chargeId;
    }

    public void declined(FailureReason reason, Instant now) {
        move(Event.DECLINED, now);
        this.failureReason = reason;
        // A provider can report a decline for a charge that went through: re-checked for 48 hours (FR-018).
        this.reconcileAt = ReconciliationSchedule.first(now);
    }

    /** The provider failed or refused before charging. No event; the request is retried by a new attempt. */
    public void technicalFailure(Instant now) {
        move(Event.PROVIDER_ERROR, now);
    }

    /** The provider may or may not have charged. Never retried; resolved by asking the provider (FR-018). */
    public void timedOut(Instant now) {
        move(Event.TIMED_OUT, now);
        this.reconcileAt = ReconciliationSchedule.first(now);
    }

    /**
     * Sent longer ago than the call timeout, with no result recorded: its worker died (ADR-018). Only a
     * SENT attempt: the provider is called only after SENT commits, so a REQUESTED attempt of any age was
     * never sent (proposed ADR-026).
     */
    public void stale(Instant now) {
        move(Event.STALE, now);
        this.reconcileAt = now;
    }

    /** The provider's record, or a repeat with the attempt's key, shows a charge. Returns the state it corrected. */
    public AttemptState resolvedCharged(String chargeId, Instant now) {
        AttemptState was = state;
        move(Event.RESOLVED_CHARGED, now);
        this.failureReason = null;
        this.chargeId = chargeId;
        this.reconcileAt = null;
        return was;
    }

    public void resolvedDeclined(FailureReason reason, Instant now) {
        move(Event.RESOLVED_DECLINED, now);
        this.failureReason = reason;
        this.reconcileChecks = 0;
        this.reconcileAt = ReconciliationSchedule.first(now);
    }

    /** A check that changed nothing: back off (5 minutes, 1 hour, then daily; DECLINED for 48 hours only). */
    public void checkedWithoutChange(Instant now) {
        if (state != AttemptState.UNKNOWN && state != AttemptState.DECLINED) {
            throw new IllegalStateException("Attempt " + id + " is " + state + ": nothing to reconcile");
        }
        reconcileChecks++;
        reconcileAt = state == AttemptState.DECLINED
                ? ReconciliationSchedule.afterFailed(reconcileChecks, updatedAt, now).orElse(null)
                : ReconciliationSchedule.after(reconcileChecks, now);
    }

    private void move(Event event, Instant now) {
        state = next(state, event).orElseThrow(() -> new IllegalStateException(
                "Attempt " + id + " is " + state + ": " + event + " is not allowed (ADR-018)"));
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> requestId() {
        return Optional.ofNullable(requestId);
    }

    public String reference() {
        return reference;
    }

    public int number() {
        return number;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public UUID accountId() {
        return accountId;
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    public String paymentToken() {
        return paymentToken;
    }

    public AttemptState state() {
        return state;
    }

    public Optional<FailureReason> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public Optional<Instant> sentAt() {
        return Optional.ofNullable(sentAt);
    }

    public Optional<Instant> reconcileAt() {
        return Optional.ofNullable(reconcileAt);
    }
}
