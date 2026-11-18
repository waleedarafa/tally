package com.tally;

import com.tally.platform.TestClock;
import com.tally.platform.TestClockConfiguration;
import com.tally.support.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: the full application against a real PostgreSQL, with a
 * {@link TestClock} in place of the system clock (D10). Pollers are off: tests drive queues
 * directly, unless a test switches polling on. Test-only tables come from {@code db/testmigration}.
 * The settings sit on {@code @TestPropertySource}, so a subclass can redeclare {@code @SpringBootTest}
 * (for example with a real port) and still inherit them.
 * One container is shared by every test class in the run (singleton container pattern); each test class gets
 * a database of its own in it, copied from a migrated template ({@link com.tally.support.PerContextDatabase},
 * ADR-029).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "tally.platform.polling-enabled=false",
        // The health and metrics endpoints on a management port of their own (Slice 4, brief rule 10).
        "management.server.port=0"
})
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    // Every Spring context and hand-started instance keeps its own connection pool (10 by default); the
    // default limit of 100 connections is too low once several are alive in one test run.
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "max_connections=500");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestClock clock;

    /** Tokens are checked with the test issuer's key set (Slice 4 PR-3, ADR-032). */
    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        TestTokens.billingSettings().forEach((name, value) -> registry.add(name, () -> value));
    }
}
