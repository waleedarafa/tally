package com.tally.payment.domain;

import com.tally.payment.AttemptState;
import com.tally.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * ADR-041: an invoice is collected through one collection request, one per reference, never
 * deleted. It is the aggregate root; its {@link PaymentAttempt}s are the tries. Created only by
 * {@link CollectionRequestRepository#insertIfAbsent}, an atomic insert-or-nothing.
 */
@Entity
@Table(schema = "payment", name = "collection_request")
public class CollectionRequest {

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
    private CollectionOutcome outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CollectionRequest() {
    }

    /** Records its attempt's outcome, in the same transaction as the attempt changes (one aggregate). */
    public void settle(AttemptState attempt, Instant now) {
        CollectionOutcome next = switch (attempt) {
            case SUCCEEDED -> CollectionOutcome.SUCCEEDED;
            case FAILED -> CollectionOutcome.FAILED;
            default -> CollectionOutcome.OPEN;
        };
        if (next != outcome) {
            outcome = next;
            updatedAt = now;
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

    public CollectionOutcome outcome() {
        return outcome;
    }
}
