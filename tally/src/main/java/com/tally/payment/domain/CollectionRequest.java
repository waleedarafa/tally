package com.tally.payment.domain;

import com.tally.payment.AttemptState;
import com.tally.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-041: an invoice is collected through one collection request, one per reference, never
 * deleted. It is the aggregate root; its {@link PaymentAttempt}s are the tries. Created only by
 * {@link CollectionRequests#addIfAbsent}, an atomic insert-or-nothing.
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

    /**
     * The Payment aggregate's version: the sequence its events carry (ADR-016). 1 when requested; each
     * change of outcome adds one.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /** The highest retry Collections asked for (0: none). Each retry is made once (Slice 1R PR-8). */
    @Column(nullable = false)
    private int retries;

    protected CollectionRequest() {
    }

    /** Records its attempt's outcome, in the same transaction as the attempt changes (one aggregate). */
    public void settle(AttemptState attempt, Instant now) {
        CollectionOutcome next = switch (attempt) {
            case SUCCEEDED -> CollectionOutcome.SUCCEEDED;
            case DECLINED -> CollectionOutcome.FAILED;
            // REQUESTED, SENT, UNKNOWN, or a technical failure whose retry is queued.
            default -> CollectionOutcome.OPEN;
        };
        if (next != outcome) {
            outcome = next;
            updatedAt = now;
        }
    }

    /**
     * Records that Collections asked for retry {@code retry}. Returns false, changing nothing, if that
     * retry, or a later one, was already asked for: a retry asked for twice is made once. Two concurrent
     * calls are told apart by the optimistic lock on {@code version} (CLAUDE.md rule 7).
     */
    public boolean retryRequested(int retry, Instant now) {
        if (retry <= retries) {
            return false;
        }
        retries = retry;
        updatedAt = now;
        return true;
    }

    public int retries() {
        return retries;
    }

    public long version() {
        return version;
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
