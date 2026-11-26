package com.tally.flags.application;

import com.tally.flags.FlagChangeView;
import com.tally.flags.FlagConflictException;
import com.tally.flags.FlagNotFoundException;
import com.tally.flags.FlagReport;
import com.tally.flags.FlagView;
import com.tally.flags.FlagsAdminApi;
import com.tally.flags.InvalidFlagRequestException;
import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.FlagChange;
import com.tally.flags.domain.Flags;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The flags admin service: the admin operations of the flags module (ADR-053). */
@Service
class FlagsAdminService implements FlagsAdminApi {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private static final Logger log = LoggerFactory.getLogger(FlagsAdminService.class);

    private final FlagCatalogue catalogue;
    private final Clock clock;

    FlagsAdminService(FlagCatalogue catalogue, Clock clock) {
        this.catalogue = catalogue;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public FlagReport report() {
        Instant now = clock.instant();
        Flags flags = catalogue.load();
        List<FlagView> views = new ArrayList<>();
        for (String name : flags.names()) {
            views.add(viewOf(flags, name, now));
        }
        List<String> overdue = new ArrayList<>();
        for (FlagView view : views) {
            if (view.overdue()) {
                overdue.add(view.name());
            }
        }
        return new FlagReport(now, views, overdue);
    }

    @Override
    @Transactional(readOnly = true)
    public FlagView flag(String name) {
        validateName(name);
        Flags flags = catalogue.load();
        requireKnown(flags, name);
        return viewOf(flags, name, clock.instant());
    }

    @Override
    @Transactional
    public FlagView define(String name, String staff) {
        validateName(name);
        Flags flags = catalogue.loadForUpdate();
        if (flags.names().contains(name)) {
            throw new FlagConflictException("Flag already defined: " + name);
        }
        return afterChange(flags, name, 0, staff, now -> flags.define(name, now));
    }

    @Override
    @Transactional
    public FlagView setDefault(String name, boolean on, String staff) {
        validateName(name);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        return afterChange(flags, name, flags.changes(name).size(), staff, now -> flags.setDefault(name, on, now));
    }

    @Override
    @Transactional
    public FlagView assignAccount(String name, UUID accountId, boolean on, String staff) {
        validateName(name);
        validateAccount(accountId);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        return afterChange(flags, name, flags.changes(name).size(), staff, now -> flags.assignAccount(name, accountId, on, now));
    }

    @Override
    @Transactional
    public FlagView clearAccount(String name, UUID accountId, String staff) {
        validateName(name);
        validateAccount(accountId);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        return afterChange(flags, name, flags.changes(name).size(), staff, now -> flags.clearAccountAssignment(name, accountId, now));
    }

    @Override
    @Transactional
    public FlagView assignCountry(String name, String country, boolean on, String staff) {
        validateName(name);
        String code = validateCountry(country);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        return afterChange(flags, name, flags.changes(name).size(), staff, now -> flags.assignCountry(name, code, on, now));
    }

    @Override
    @Transactional
    public FlagView clearCountry(String name, String country, String staff) {
        validateName(name);
        String code = validateCountry(country);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        return afterChange(flags, name, flags.changes(name).size(), staff, now -> flags.clearCountryAssignment(name, code, now));
    }

    @Override
    @Transactional
    public FlagView release(String name, String staff) {
        validateName(name);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        return afterChange(flags, name, flags.changes(name).size(), staff, now -> flags.release(name, now));
    }

    @Override
    @Transactional
    public void remove(String name, String staff) {
        validateName(name);
        Flags flags = catalogue.loadForUpdate();
        requireKnown(flags, name);
        Instant now = clock.instant();
        try {
            flags.remove(name, now);
        } catch (IllegalArgumentException e) {
            throw new FlagConflictException(e.getMessage());
        }
        catalogue.save(flags);
        logChange(staff, name, "removed");
    }

    private FlagView afterChange(Flags flags, String name, int before, String staff, FlagOperation operation) {
        Instant now = clock.instant();
        try {
            operation.run(now);
        } catch (IllegalArgumentException e) {
            throw new FlagConflictException(e.getMessage());
        }
        boolean logged = flags.changes(name).size() > before;
        if (logged) {
            catalogue.save(flags);
        }
        logChange(staff, name, logged ? flags.changes(name).getLast().description() : "no change");
        return viewOf(flags, name, now);
    }

    private FlagView viewOf(Flags flags, String name, Instant now) {
        Instant releasedAt = flags.releasedAt(name);
        List<FlagChange> changes = flags.changes(name);
        List<FlagChangeView> changeViews = new ArrayList<>();
        for (FlagChange change : changes) {
            changeViews.add(new FlagChangeView(change.at(), change.description()));
        }
        return new FlagView(name, flags.defaultOn(name), flags.accountAssignments(name),
                flags.countryAssignments(name), releasedAt,
                releasedAt == null ? null : releasedAt.plus(30, ChronoUnit.DAYS),
                flags.isOverdue(name, now), changes.size(),
                changes.isEmpty() ? null : changes.getLast().at(), changeViews);
    }

    private void logChange(String staff, String name, String change) {
        log.info("flags admin: staff={} flag={} change={}", staff, name, change);
    }

    private static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidFlagRequestException("Flag name must match ^[a-z][a-z0-9-]*$: " + name);
        }
    }

    private static String validateCountry(String country) {
        String code = country == null ? null : country.trim().toUpperCase(Locale.ROOT);
        if (code == null || !COUNTRY.matcher(code).matches()) {
            throw new InvalidFlagRequestException("Country must be an ISO 3166-1 alpha-2 code: " + country);
        }
        return code;
    }

    private static void validateAccount(UUID accountId) {
        if (accountId == null) {
            throw new InvalidFlagRequestException("accountId is required");
        }
    }

    private static void requireKnown(Flags flags, String name) {
        if (!flags.names().contains(name)) {
            throw new FlagNotFoundException("Unknown flag: " + name);
        }
    }

    private interface FlagOperation {
        void run(Instant now);
    }
}
