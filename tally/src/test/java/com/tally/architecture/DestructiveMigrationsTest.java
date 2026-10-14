package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Expand and contract (ADR-009) must not rest on discipline alone (Design Record, "Required before production").
 * During a rolling deploy the previous release still runs against the new schema. A migration that drops,
 * renames or tightens something the previous release uses breaks it. So every such statement needs a
 * {@code -- contract: <reason>} comment directly above it: the reason says why the previous release no longer
 * needs what the statement removes, usually because an earlier release stopped using it.
 *
 * <p>V1 to V30 were applied before this check existed. A marker added to them now would change their Flyway
 * checksum, so their findings are pinned here instead, exactly. A changed old migration fails too.
 */
class DestructiveMigrationsTest {

    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    static final int LAST_MIGRATION_BEFORE_THE_CHECK = 30;
    static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.+\\.sql");

    /** Unmarked destructive statements in migrations applied before the check, as "line rule". */
    static final Map<String, List<String>> APPLIED_BEFORE_THE_CHECK = Map.of(
            "V16__rename_catalog_to_offers.sql", List.of("4 rename"),
            "V17__offers_versions_and_kinds.sql", List.of("48 drop table", "49 drop table"),
            "V19__quote_reason_jsonb.sql", List.of("4 change column type"),
            "V20__calendar_to_subscription.sql", List.of("15 set not null", "17 set not null", "66 drop table",
                    "67 drop table"),
            "V22__payment_attempt_key.sql", List.of("9 set not null"),
            "V27__platform_idempotency_keys.sql", List.of("5 rename", "6 change column type"),
            "V28__rename_entitlement_to_access.sql", List.of("4 rename"));

    @Test
    void everyDestructiveStatementCarriesAContractMarker() throws IOException {
        Map<String, List<String>> unexpected = new TreeMap<>();
        for (Path file : migrations()) {
            String name = file.getFileName().toString();
            List<String> unmarked = DestructiveStatements.unmarked(Files.readString(file)).stream()
                    .map(f -> f.line() + " " + f.rule()).toList();
            if (!unmarked.equals(APPLIED_BEFORE_THE_CHECK.getOrDefault(name, List.of()))) {
                unexpected.put(name, unmarked);
            }
        }

        assertThat(unexpected)
                .as("A migration drops, renames or tightens something the previous release may still use"
                        + " (ADR-009, expand and contract). Split it: expand in one release, contract in a later"
                        + " one. On the contract statement, add a comment directly above it:"
                        + " '-- contract: <why the previous release no longer needs this>'. A migration listed in"
                        + " APPLIED_BEFORE_THE_CHECK is applied: never edit it")
                .isEmpty();
    }

    @Test
    void everyPinnedMigrationStillExists() throws IOException {
        List<String> names = migrations().stream().map(f -> f.getFileName().toString()).toList();

        assertThat(names).containsAll(APPLIED_BEFORE_THE_CHECK.keySet());
    }

    @Test
    void onlyMigrationsAppliedBeforeTheCheckArePinned() {
        assertThat(APPLIED_BEFORE_THE_CHECK.keySet()).allSatisfy(name -> assertThat(version(name))
                .as("%s: a new migration gets a contract marker, not a pinned exception", name)
                .isLessThanOrEqualTo(LAST_MIGRATION_BEFORE_THE_CHECK));
    }

    @Test
    void theCheckReadsTheMigrations() throws IOException {
        assertThat(migrations()).hasSizeGreaterThanOrEqualTo(LAST_MIGRATION_BEFORE_THE_CHECK);
    }

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static int version(String name) {
        Matcher m = VERSIONED.matcher(name);
        assertThat(m.matches()).as("%s is not a versioned migration", name).isTrue();
        return Integer.parseInt(m.group(1));
    }
}
