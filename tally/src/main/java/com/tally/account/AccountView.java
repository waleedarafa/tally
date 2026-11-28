package com.tally.account;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * An account as other modules see it. {@code paymentToken} is empty until sign-up records one.
 * {@code standing} is set by Collections only (F3).
 * {@code billingDay} is empty until the first subscription starts (ADR-043).
 * {@code country} is an ISO 3166-1 alpha-2 code set at open (WAR-66).
 */
public record AccountView(UUID id, Currency currency, ZoneId timeZone, String country, Optional<String> paymentToken,
        Standing standing, OptionalInt billingDay) {
}
