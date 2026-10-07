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
                "account", "catalog", "subscription", "invoicing", "payment", "entitlement");
    }

    @Autowired
    Clock injectedClock;

    @Test
    void injectsTheTestClock() {
        assertThat(injectedClock).isSameAs(clock);
    }
}
