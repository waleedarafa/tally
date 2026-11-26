package com.tally.flags;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One flag as the admin API shows it (ADR-053). {@code releasedAt} and {@code removeBy} are null
 * before release; {@code removeBy} is the release instant plus 30 days. Accounts are sorted by
 * account id, countries by code, both unmodifiable, and {@code changes} is the full log in order,
 * unmodifiable.
 */
public record FlagView(String name, boolean defaultOn, Map<UUID, Boolean> accounts, Map<String, Boolean> countries,
        Instant releasedAt, Instant removeBy, boolean overdue, int changeCount, Instant lastChangeAt,
        List<FlagChangeView> changes) {

    public FlagView {
        Objects.requireNonNull(name, "name");
        accounts = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(accounts, "accounts")));
        countries = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(countries, "countries")));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}
