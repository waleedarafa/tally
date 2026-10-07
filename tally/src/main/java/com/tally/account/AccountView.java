package com.tally.account;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/** An account as other modules see it. {@code paymentToken} is empty until checkout records one. */
public record AccountView(UUID id, Currency currency, ZoneId timeZone, Optional<String> paymentToken) {
}
