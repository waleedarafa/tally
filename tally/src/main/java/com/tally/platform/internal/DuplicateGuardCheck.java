package com.tally.platform.internal;

import com.tally.platform.QueueDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-037: a queue's key is not a permanent guard, because finished rows are deleted. Every queue a
 * module registers must name a unique constraint or unique index in a business table. Registration
 * fails fast if it is missing, does not exist, is not unique, or sits on the queue table itself.
 */
final class DuplicateGuardCheck {

    private DuplicateGuardCheck() {
    }

    static void verify(JdbcTemplate jdbc, QueueDefinition definition) {
        String guard = definition.duplicateGuard();
        if (guard == null) {
            throw new IllegalStateException("Queue " + definition.name()
                    + " names no duplicate guard in a business table (ADR-037)");
        }
        String[] name = guard.split("\\.");
        // A unique constraint creates a unique index of the same name, so one lookup covers both.
        String guardedTable = jdbc.query("""
                SELECT tn.nspname || '.' || t.relname
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace tn ON tn.oid = t.relnamespace
                WHERE i.indisunique AND n.nspname = ? AND c.relname = ?
                """, rs -> rs.next() ? rs.getString(1) : null, name[0], name[1]);
        if (guardedTable == null) {
            throw new IllegalStateException("Queue " + definition.name() + ": duplicate guard " + guard
                    + " is not a unique constraint or unique index");
        }
        if (guardedTable.equals(definition.table())) {
            throw new IllegalStateException("Queue " + definition.name() + ": duplicate guard " + guard
                    + " is on the queue table itself, whose rows are deleted (ADR-037)");
        }
    }
}
