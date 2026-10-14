package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What {@link DestructiveStatements} flags, and what it leaves alone (ADR-009, E-06). */
class DestructiveStatementsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            DROP TABLE offers.plan                                                   | drop table
            drop schema catalog cascade                                              | drop schema
            DROP VIEW support.overview                                               | drop view
            DROP MATERIALIZED VIEW support.overview                                  | drop view
            ALTER TABLE invoicing.invoice DROP COLUMN billed_for                     | drop column
            ALTER TABLE invoicing.invoice DROP billed_for                            | drop column
            ALTER SCHEMA catalog RENAME TO offers                                    | rename
            ALTER TABLE t RENAME COLUMN account_id TO scope                          | rename
            ALTER TABLE t ALTER COLUMN scope TYPE TEXT USING scope::text             | change column type
            ALTER TABLE t ALTER scope SET DATA TYPE TEXT                             | change column type
            ALTER TABLE t ALTER COLUMN anchor_day SET NOT NULL                       | set not null
            ALTER TABLE t ADD COLUMN standing TEXT NOT NULL                          | add not-null column without default
            ALTER TABLE t ADD standing TEXT NOT NULL                                 | add not-null column without default
            TRUNCATE subscription.renewal                                            | truncate
            """)
    void flagsAChangeThatBreaksThePreviousReleaseOrLosesData(String sql, String rule) {
        assertThat(DestructiveStatements.unmarked(sql)).extracting(DestructiveStatements.Finding::rule)
                .containsExactly(rule);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE t (id UUID PRIMARY KEY, standing TEXT NOT NULL)",
            "ALTER TABLE t ADD COLUMN standing TEXT NOT NULL DEFAULT 'GOOD'",
            "ALTER TABLE t ADD COLUMN retries INT NOT NULL DEFAULT 0, ADD COLUMN note TEXT",
            "ALTER TABLE t ADD COLUMN note TEXT",
            "ALTER TABLE t ALTER COLUMN note DROP NOT NULL",
            "ALTER TABLE t ALTER COLUMN note DROP DEFAULT",
            "ALTER TABLE t DROP CONSTRAINT t_pkey",
            "ALTER TABLE t ADD CONSTRAINT t_pkey PRIMARY KEY (scope, key)",
            "CREATE INDEX t_due ON t (due_at)",
            "DROP INDEX t_due",
            "ALTER TYPE payment.outcome ADD VALUE 'UNKNOWN'",
            "UPDATE t SET note = 'drop table t; rename'",
            "-- drop table t\nCREATE TABLE t (id UUID)",
            "/* ALTER TABLE t DROP COLUMN x */ CREATE TABLE t (id UUID)",
            "DO $$ BEGIN EXECUTE 'DROP TABLE t'; END $$"
    })
    void leavesAloneWhatThePreviousReleaseCanLiveWith(String sql) {
        assertThat(DestructiveStatements.unmarked(sql)).isEmpty();
    }

    @Test
    void aContractMarkerAboveTheStatementAllowsIt() {
        String sql = """
                -- contract: nothing has read offers.plan since V17 shipped
                DROP TABLE offers.plan;
                """;

        assertThat(DestructiveStatements.unmarked(sql)).isEmpty();
    }

    @Test
    void aMarkerCoversOnlyTheNextStatement() {
        String sql = """
                -- contract: nothing has read offers.plan since V17 shipped
                DROP TABLE offers.plan;
                DROP TABLE offers.plan_price;
                """;

        assertThat(DestructiveStatements.unmarked(sql))
                .containsExactly(new DestructiveStatements.Finding(3, "drop table"));
    }

    @Test
    void aMarkerNeedsAReason() {
        String sql = """
                -- contract:
                DROP TABLE offers.plan;
                """;

        assertThat(DestructiveStatements.unmarked(sql)).hasSize(1);
    }

    @Test
    void reportsTheLineWhereTheStatementStarts() {
        String sql = """
                CREATE TABLE t (id UUID);

                -- not a marker
                ALTER TABLE t
                    DROP COLUMN x;
                """;

        assertThat(DestructiveStatements.unmarked(sql))
                .isEqualTo(List.of(new DestructiveStatements.Finding(4, "drop column")));
    }
}
