package com.tally.account.domain;

import com.tally.account.Standing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(schema = "account", name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String currency;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "payment_token")
    private String paymentToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Set only by Collections (F3). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Standing standing = Standing.GOOD;

    @Column(name = "standing_cause")
    private String standingCause;

    /**
     * The account's version: an optimistic lock, and the sequence {@code StandingChanged} carries (ADR-016). Of two
     * changes of standing at the same time, one commits and the other fails.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Account() {
    }

    public Account(UUID id, Currency currency, ZoneId timeZone, Instant createdAt) {
        this.id = id;
        this.currency = currency.getCurrencyCode();
        this.timeZone = timeZone.getId();
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public ZoneId timeZone() {
        return ZoneId.of(timeZone);
    }

    public Optional<String> paymentToken() {
        return Optional.ofNullable(paymentToken);
    }

    public Standing standing() {
        return standing;
    }

    public long version() {
        return version;
    }

    /** Sets the standing; returns false, and changes nothing, if the account already has it. */
    public boolean setStanding(Standing standing, String cause) {
        if (standing == null || standing == Standing.UNKNOWN) {
            throw new IllegalArgumentException("A standing is GOOD, OVERDUE or BLOCKED: " + standing);
        }
        if (this.standing == standing) {
            return false;
        }
        this.standing = standing;
        this.standingCause = cause;
        return true;
    }

    public void recordPaymentToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Payment token is required");
        }
        this.paymentToken = token;
    }
}
