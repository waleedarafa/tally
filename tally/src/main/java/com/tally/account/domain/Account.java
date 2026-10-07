package com.tally.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    public void recordPaymentToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Payment token is required");
        }
        this.paymentToken = token;
    }
}
