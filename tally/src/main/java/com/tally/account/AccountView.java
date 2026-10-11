package com.tally.account;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * An account as other modules see it. {@code paymentToken} is empty until sign-up records one.
 * {@code standing} is set by Collections only (F3).
 */
public record AccountView(UUID id, Currency currency, ZoneId timeZone, Optional<String> paymentToken,
        Standing standing) {
}
