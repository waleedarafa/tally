package com.tally.flags.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The feature flag catalogue (ADR-010, ADR-048): a flag is defined off by default, assigned per account
 * or country, logged on change, and is fully released only when the default is on and no assignment is
 * off. A fully released flag is overdue more than 30 days after release and must then be removed.
 *
 * <p>An account assignment wins over a country assignment, which wins over the default. Names and
 * countries are normalised as they are accepted: the name is trimmed, the country is trimmed and
 * upper-cased. Names are matched by the trimmed value, so callers that want a different spelling
 * of the same flag must normalise first.
 */
public final class Flags {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private final Map<String, Flag> flags = new HashMap<>();

    /** Defines a new flag, off by default and not yet released. */
    public void define(String name, Instant at) {
        String flagName = normaliseName(name);
        Objects.requireNonNull(at, "at");
        if (flags.containsKey(flagName)) {
            throw new IllegalArgumentException("Flag already defined: " + flagName);
        }
        Flag flag = new Flag(flagName);
        flag.changes.add(new FlagChange(at, "defined, default off"));
        flags.put(flagName, flag);
    }

    /** The flag's effective state for an account in a country: account, then country, then default. */
    public boolean isOn(String name, UUID accountId, String country) {
        Flag flag = flag(name);
        requireAccountId(accountId);
        String countryCode = normaliseCountry(country);
        if (flag.accountAssignments.containsKey(accountId)) {
            return flag.accountAssignments.get(accountId);
        }
        if (flag.countryAssignments.containsKey(countryCode)) {
            return flag.countryAssignments.get(countryCode);
        }
        return flag.defaultOn;
    }

    /** Sets the default for everyone without an assignment. Only allowed while the flag is not released. */
    public void setDefault(String name, boolean on, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        flag.rejectIfReleased(name);
        flag.defaultOn = on;
        flag.changes.add(new FlagChange(at, on ? "default set to on" : "default set to off"));
    }

    /** Assigns the flag for one account. Only allowed while the flag is not released. */
    public void assignAccount(String name, UUID accountId, boolean on, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        requireAccountId(accountId);
        flag.rejectIfReleased(name);
        flag.accountAssignments.put(accountId, on);
        flag.changes.add(new FlagChange(at, "account " + accountId + " set to " + (on ? "on" : "off")));
    }

    /** Assigns the flag for one country. Only allowed while the flag is not released. */
    public void assignCountry(String name, String country, boolean on, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        String countryCode = normaliseCountry(country);
        flag.rejectIfReleased(name);
        flag.countryAssignments.put(countryCode, on);
        flag.changes.add(new FlagChange(at, "country " + countryCode + " set to " + (on ? "on" : "off")));
    }

    /** Clears the account assignment. If there is none, the flag is left as it is and nothing is logged. */
    public void clearAccountAssignment(String name, UUID accountId, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        requireAccountId(accountId);
        flag.rejectIfReleased(name);
        if (!flag.accountAssignments.containsKey(accountId)) {
            // No assignment to clear: nothing to do, and nothing to log.
            return;
        }
        flag.accountAssignments.remove(accountId);
        flag.changes.add(new FlagChange(at, "account " + accountId + " assignment cleared"));
    }

    /** Clears the country assignment. If there is none, the flag is left as it is and nothing is logged. */
    public void clearCountryAssignment(String name, String country, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        String countryCode = normaliseCountry(country);
        flag.rejectIfReleased(name);
        if (!flag.countryAssignments.containsKey(countryCode)) {
            // No assignment to clear: nothing to do, and nothing to log.
            return;
        }
        flag.countryAssignments.remove(countryCode);
        flag.changes.add(new FlagChange(at, "country " + countryCode + " assignment cleared"));
    }

    /**
     * Fully releases the flag. Only allowed when the default is on and no assignment (account or
     * country) is off; on-assignments may remain. After this the flag is fully released and only
     * {@link #remove} is allowed.
     */
    public void release(String name, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        if (flag.releasedAt != null) {
            throw new IllegalArgumentException("Flag already released: " + flag.name);
        }
        if (!flag.defaultOn) {
            throw new IllegalArgumentException("Flag " + flag.name + " is not released while the default is off");
        }
        for (boolean value : flag.accountAssignments.values()) {
            if (!value) {
                throw new IllegalArgumentException("Flag " + flag.name + " is not released while an account assignment is off");
            }
        }
        for (boolean value : flag.countryAssignments.values()) {
            if (!value) {
                throw new IllegalArgumentException("Flag " + flag.name + " is not released while a country assignment is off");
            }
        }
        flag.releasedAt = at;
        flag.changes.add(new FlagChange(at, "fully released"));
    }

    /**
     * Removes the flag. After this the flag is gone: {@link #define} may reuse the name as a new flag,
     * and the old change log is discarded.
     */
    public void remove(String name, Instant at) {
        Flag flag = flag(name);
        Objects.requireNonNull(at, "at");
        flags.remove(flag.name);
    }

    /** More than 30 days after full release; never overdue before release. */
    public boolean isOverdue(String name, Instant asOf) {
        Flag flag = flag(name);
        Objects.requireNonNull(asOf, "asOf");
        if (flag.releasedAt == null) {
            return false;
        }
        return asOf.isAfter(flag.releasedAt.plus(30, ChronoUnit.DAYS));
    }

    /** The changes logged for the flag, in the order they were appended. */
    public List<FlagChange> changes(String name) {
        Flag flag = flag(name);
        return Collections.unmodifiableList(flag.changes);
    }

    private Flag flag(String name) {
        String flagName = normaliseName(name);
        Flag flag = flags.get(flagName);
        if (flag == null) {
            throw new IllegalArgumentException("Unknown flag: " + flagName);
        }
        return flag;
    }

    private static void requireAccountId(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
    }

    private static String normaliseName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmed = name.trim();
        if (!NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Name must match ^[a-z][a-z0-9-]*$: " + trimmed);
        }
        return trimmed;
    }

    private static String normaliseCountry(String country) {
        if (country == null) {
            throw new IllegalArgumentException("Country is required");
        }
        String code = country.trim().toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(code).matches()) {
            throw new IllegalArgumentException("Country must be an ISO 3166-1 alpha-2 code: " + country);
        }
        return code;
    }

    private static final class Flag {
        private final String name;
        private boolean defaultOn = false;
        private final Map<UUID, Boolean> accountAssignments = new HashMap<>();
        private final Map<String, Boolean> countryAssignments = new HashMap<>();
        private final List<FlagChange> changes = new ArrayList<>();
        private Instant releasedAt = null;

        private Flag(String name) {
            this.name = name;
        }

        /** Throws if the flag is fully released; only {@code remove} is allowed after that. */
        private void rejectIfReleased(String name) {
            if (releasedAt != null) {
                throw new IllegalArgumentException("Flag already released: " + name);
            }
        }
    }
}
