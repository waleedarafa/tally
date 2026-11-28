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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
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

    @Column(nullable = false)
    private String country;

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

    /**
     * The account's billing day (ADR-043): set once, by the first subscription, from its start instant in the
     * account's time zone, and never changed. Unset until a subscription's start sets it.
     */
    @Column(name = "billing_day")
    private Integer billingDay;

    protected Account() {
    }

    public Account(UUID id, Currency currency, ZoneId timeZone, Instant createdAt) {
        this.id = id;
        this.currency = currency.getCurrencyCode();
        this.timeZone = timeZone.getId();
        this.country = requireCountry("US");
        this.createdAt = createdAt;
    }

    public Account(UUID id, Currency currency, ZoneId timeZone, String country, Instant createdAt) {
        this.id = id;
        this.currency = currency.getCurrencyCode();
        this.timeZone = timeZone.getId();
        this.country = requireCountry(country);
        this.createdAt = createdAt;
    }

    private static String requireCountry(String country) {
        if (country == null || country.isBlank()) {
            throw new IllegalArgumentException("Country is required (ISO 3166-1 alpha-2)");
        }
        String normalised = country.trim().toUpperCase(Locale.ROOT);
        if (!normalised.matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException("Country must be an ISO 3166-1 alpha-2 code: " + country);
        }
        return normalised;
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

    public String country() {
        return country;
    }

    public Optional<String> paymentToken() {
        return Optional.ofNullable(paymentToken);
    }

    /** The account's billing day (1-31), or empty while no subscription has started. */
    public OptionalInt billingDay() {
        return billingDay == null ? OptionalInt.empty() : OptionalInt.of(billingDay);
    }

    /**
     * Sets the billing day from a subscription start, the local calendar day of {@code startedAt} in this account's
     * time zone. Returns true only if this call set the day; an already-set day is left unchanged and returned as
     * false, even when the new start would be a different day.
     */
    public boolean setBillingDayFrom(Instant startedAt) {
        if (billingDay != null) {
            return false;
        }
        LocalDate startedOn = LocalDate.ofInstant(startedAt, timeZone());
        billingDay = startedOn.getDayOfMonth();
        return true;
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
