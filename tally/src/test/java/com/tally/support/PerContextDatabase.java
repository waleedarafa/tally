package com.tally.support;

import com.tally.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Test isolation (ADR-029, proposed): no test class meets a row it did not create. Every test class that extends
 * {@link AbstractIntegrationTest} gets a Spring context and a PostgreSQL database of its own, and so does every
 * {@link TallyInstances}. Each database is a copy of a template migrated once per run
 * ({@code CREATE DATABASE ... TEMPLATE ...}, about 50 ms). The test class is part of the customizer's equality, so a
 * class never reuses another class's context or database. Registered in {@code META-INF/spring.factories}, which,
 * unlike {@code @ContextConfiguration}, leaves Spring Boot's detection of a test class's nested configuration alone.
 */
public final class PerContextDatabase implements ContextCustomizerFactory {

    private static final String TEMPLATE = "tally_template";
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static boolean templateReady;

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attributes) {
        return AbstractIntegrationTest.class.isAssignableFrom(testClass) ? new Customizer(testClass) : null;
    }

    /** A database of its own, copied from the migrated template; returns its JDBC URL. */
    public static String newDatabase(String prefix) {
        String name = prefix + "_" + NEXT.incrementAndGet();
        create(name);
        return url(name);
    }

    /** Equal only for the same test class: every test class gets its own context, so its own database. */
    static final class Customizer implements ContextCustomizer {

        private final Class<?> testClass;

        Customizer(Class<?> testClass) {
            this.testClass = testClass;
        }

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("per-context-database", Map.of(
                    "spring.datasource.url", newDatabase("tally_ctx"),
                    "spring.datasource.username", AbstractIntegrationTest.POSTGRES.getUsername(),
                    "spring.datasource.password", AbstractIntegrationTest.POSTGRES.getPassword())));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Customizer that && that.testClass == testClass;
        }

        @Override
        public int hashCode() {
            return testClass.hashCode();
        }
    }

    private static synchronized void create(String name) {
        AbstractIntegrationTest.POSTGRES.start();
        if (!templateReady) {
            execute("CREATE DATABASE " + TEMPLATE);
            Flyway.configure()
                    .dataSource(url(TEMPLATE), AbstractIntegrationTest.POSTGRES.getUsername(),
                            AbstractIntegrationTest.POSTGRES.getPassword())
                    .locations("classpath:db/migration", "classpath:db/testmigration")
                    .defaultSchema("public")
                    .load()
                    .migrate();
            templateReady = true;
        }
        execute("CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE);
    }

    private static void execute(String statement) {
        try (Connection c = DriverManager.getConnection(AbstractIntegrationTest.POSTGRES.getJdbcUrl(),
                AbstractIntegrationTest.POSTGRES.getUsername(), AbstractIntegrationTest.POSTGRES.getPassword())) {
            c.createStatement().execute(statement);
        } catch (SQLException e) {
            throw new IllegalStateException(statement, e);
        }
    }

    private static String url(String database) {
        String base = AbstractIntegrationTest.POSTGRES.getJdbcUrl();
        return base.replaceFirst("/" + AbstractIntegrationTest.POSTGRES.getDatabaseName() + "(\\?|$)", "/" + database + "$1");
    }
}
