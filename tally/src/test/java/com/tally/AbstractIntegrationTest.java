package com.tally;

import com.tally.platform.TestClock;
import com.tally.platform.TestClockConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: the full application against a real PostgreSQL, with a
 * {@link TestClock} in place of the system clock (D4).
 * One container is shared by every test class in the run (singleton container pattern),
 * so Spring can also reuse its cached contexts.
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestClock clock;
}
