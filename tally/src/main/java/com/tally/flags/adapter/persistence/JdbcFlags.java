package com.tally.flags.adapter.persistence;

import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.FlagChange;
import com.tally.flags.domain.Flags;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link FlagCatalogue} over the {@code flags} schema (V56, ADR-049). */
@Repository
class JdbcFlags implements FlagCatalogue {

    /** One row of the {@code flags.flag} table. */
    private record Row(String name, boolean defaultOn, Instant releasedAt) {
    }

    // advisory lock key of the flags catalogue (ADR-053)
    private static final long LOCK_KEY = 7_605_001_001L;

    private final JdbcTemplate jdbc;

    JdbcFlags(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Flags load() {
        Flags flags = new Flags();
        List<Row> rows = jdbc.query("SELECT name, default_on, released_at FROM flags.flag ORDER BY name",
                (rs, rowNum) -> new Row(rs.getString("name"), rs.getBoolean("default_on"),
                        rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant()));
        for (Row row : rows) {
            Map<UUID, Boolean> accounts = new HashMap<>();
            jdbc.query("SELECT account_id, is_on FROM flags.account_assignment WHERE flag_name = ?", rs -> {
                accounts.put(rs.getObject("account_id", UUID.class), rs.getBoolean("is_on"));
            }, row.name());
            Map<String, Boolean> countries = new HashMap<>();
            jdbc.query("SELECT country, is_on FROM flags.country_assignment WHERE flag_name = ?", rs -> {
                countries.put(rs.getString("country"), rs.getBoolean("is_on"));
            }, row.name());
            List<FlagChange> changes = jdbc.query(
                    "SELECT at, description FROM flags.flag_change WHERE flag_name = ? ORDER BY seq",
                    (rs, rowNum) -> new FlagChange(rs.getTimestamp("at").toInstant(), rs.getString("description")),
                    row.name());
            flags.loadDefined(row.name(), row.defaultOn(), row.releasedAt(), accounts, countries, changes);
        }
        return flags;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Flags loadForUpdate() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, LOCK_KEY);
        return load();
    }

    @Override
    @Transactional
    public void save(Flags flags) {
        // The catalogue is small, so a full replace is enough: assignments and changes cascade.
        jdbc.update("DELETE FROM flags.flag");
        for (String name : flags.names()) {
            Instant releasedAt = flags.releasedAt(name);
            jdbc.update("INSERT INTO flags.flag (name, default_on, released_at) VALUES (?, ?, ?)", name,
                    flags.defaultOn(name), releasedAt == null ? null : releasedAt.atOffset(ZoneOffset.UTC));
            for (Map.Entry<UUID, Boolean> assignment : flags.accountAssignments(name).entrySet()) {
                jdbc.update("INSERT INTO flags.account_assignment (flag_name, account_id, is_on) VALUES (?, ?, ?)",
                        name, assignment.getKey(), assignment.getValue());
            }
            for (Map.Entry<String, Boolean> assignment : flags.countryAssignments(name).entrySet()) {
                jdbc.update("INSERT INTO flags.country_assignment (flag_name, country, is_on) VALUES (?, ?, ?)",
                        name, assignment.getKey(), assignment.getValue());
            }
            int seq = 1;
            for (FlagChange change : flags.changes(name)) {
                jdbc.update("INSERT INTO flags.flag_change (flag_name, seq, at, description) VALUES (?, ?, ?, ?)",
                        name, seq++, change.at().atOffset(ZoneOffset.UTC), change.description());
            }
        }
    }
}
