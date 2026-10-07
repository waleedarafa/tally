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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One attempt to collect an amount for a reference. */
@Entity
@Table(schema = "payment", name = "payment_attempt")
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String reference;

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

    protected PaymentAttempt() {
    }

    public static PaymentAttempt pending(String reference, UUID accountId, Money amount, String paymentToken,
            Instant now) {
        PaymentAttempt a = new PaymentAttempt();
        a.id = UUID.randomUUID();
        a.reference = reference;
        a.accountId = accountId;
        a.amountMinor = amount.minorUnits();
        a.currency = amount.currency().getCurrencyCode();
        a.paymentToken = paymentToken;
        a.state = AttemptState.PENDING;
        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    public void succeeded(String chargeId, Instant now) {
        requireInFlight();
        this.state = AttemptState.SUCCEEDED;
        this.chargeId = chargeId;
        this.updatedAt = now;
    }

    public void failed(FailureReason reason, Instant now) {
        requireInFlight();
        this.state = AttemptState.FAILED;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    /**
     * The provider certainly did not charge (it refused the call, or the call never reached it): the
     * attempt goes back to the queue. Not a retry of an unknown outcome.
     */
    public void returnToPending(Instant now) {
        requireInFlight();
        this.state = AttemptState.PENDING;
        this.updatedAt = now;
    }

    /** The provider may or may not have charged. Never retried; reconciled (ADR-018). */
    public void unknown(Instant now) {
        requireInFlight();
        this.state = AttemptState.UNKNOWN;
        this.updatedAt = now;
    }

    private void requireInFlight() {
        if (state != AttemptState.IN_FLIGHT) {
            throw new IllegalStateException("Attempt " + id + " is " + state + ", not IN_FLIGHT");
        }
    }

    public UUID id() {
        return id;
    }

    public String reference() {
        return reference;
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
}
