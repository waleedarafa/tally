package com.tally.platform.internal;

import com.tally.platform.IdempotencyKeys;
import com.tally.platform.IdempotencyStore;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Registers idempotency-key tables, checking each one's shape as {@link IdempotencyKeys} describes it. */
@Component
class JdbcIdempotencyKeys implements IdempotencyKeys {

    static final List<String> REQUIRED_COLUMNS = List.of("scope", "key", "request_hash", "response_status",
            "response_content_type", "response_body", "created_at", "claimed_at");

    private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final PlatformProperties properties;
    private final Clock clock;

    JdbcIdempotencyKeys(JdbcTemplate jdbc, PlatformTransactionManager transactions, PlatformProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IdempotencyStore register(String table) {
        // The name is written into SQL, so only plain identifiers are accepted.
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Table must be schema.table in lower case: " + table);
        }
        verify(table);
        return new JdbcIdempotencyStore(jdbc, transactions, table, properties.idempotency(), clock);
    }

    private void verify(String table) {
        String[] parts = table.split("\\.");
        Set<String> columns = new HashSet<>(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                String.class, parts[0], parts[1]));
        if (columns.isEmpty()) {
            throw new IllegalStateException("Idempotency-key table " + table + " does not exist");
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Idempotency-key table " + table + " lacks columns " + missing);
        }
        Integer primaryKey = jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i
                WHERE i.indrelid = ?::regclass AND i.indisprimary AND i.indnatts = 2
                  AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM pg_attribute a
                       WHERE a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)) = ARRAY['key', 'scope']
                """, Integer.class, table);
        if (primaryKey == null || primaryKey == 0) {
            throw new IllegalStateException("Idempotency-key table " + table + " needs PRIMARY KEY (scope, key)");
        }
    }
}
