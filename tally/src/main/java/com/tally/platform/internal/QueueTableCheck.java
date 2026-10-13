package com.tally.platform.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fails fast when a queue table lacks the shape described on {@code QueueDefinition}. */
final class QueueTableCheck {

    static final List<String> REQUIRED_COLUMNS = List.of(
            "id", "queue_key", "due_at", "payload", "status", "attempts",
            "claim_token", "claimed_at", "claimed_by", "last_error", "last_success_at", "finished_at", "created_at");

    private QueueTableCheck() {
    }

    static void verify(JdbcTemplate jdbc, String table) {
        String[] parts = table.split("\\.");
        Set<String> columns = new HashSet<>(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                String.class, parts[0], parts[1]));
        if (columns.isEmpty()) {
            throw new IllegalStateException("Queue table " + table + " does not exist");
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Queue table " + table + " lacks columns " + missing);
        }
        Integer uniqueKey = jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                WHERE i.indrelid = ?::regclass AND i.indisunique AND i.indnatts = 1 AND a.attname = 'queue_key'
                """, Integer.class, table);
        if (uniqueKey == null || uniqueKey == 0) {
            throw new IllegalStateException("Queue table " + table + " needs a unique constraint on queue_key");
        }
    }
}
