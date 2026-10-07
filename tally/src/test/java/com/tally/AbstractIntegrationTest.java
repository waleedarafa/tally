package com.tally;

import com.tally.platform.TestClock;
import com.tally.platform.TestClockConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: the full application against a real PostgreSQL, with a
 * {@link TestClock} in place of the system clock (D4). Pollers are off: tests drive queues
 * directly, unless a test switches polling on. Test-only tables come from {@code db/testmigration}.
 * The settings sit on {@code @TestPropertySource}, so a subclass can redeclare {@code @SpringBootTest}
 * (for example with a real port) and still inherit them.
 * One container is shared by every test class in the run (singleton container pattern),
 * so Spring can also reuse its cached contexts.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "tally.platform.polling-enabled=false"
})
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    // Every cached Spring context keeps its own connection pool (10 by default); the default limit
    // of 100 connections runs out once a dozen contexts are alive in one test run.
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "max_connections=500");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestClock clock;
}
