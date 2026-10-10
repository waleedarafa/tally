package com.tally;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TallyApplicationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void startsAndCreatesOneSchemaPerModule() {
        List<String> schemas = jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata", String.class);

        assertThat(schemas).contains(
                "account", "offers", "subscription", "invoicing", "payment", "entitlement");
        // Slice 1R PR-3 renamed the schema in one step (no production database exists).
        assertThat(schemas).doesNotContain("catalog");
    }

    @Autowired
    Clock injectedClock;

    @Test
    void injectsTheTestClock() {
        assertThat(injectedClock).isSameAs(clock);
    }
}
