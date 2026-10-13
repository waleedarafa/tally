package com.tally.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.tally.AbstractIntegrationTest;
import com.tally.support.TallyInstances;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

/**
 * ADR-015's start-up check: every approved kind of offer has a pricing rule, or Tally refuses to start.
 * Starts whole instances on the shared test database.
 */
class PricingRulesStartupCheckIT {

    @Configuration(proxyBeanMethods = false)
    static class NothingExtra {
    }

    final TallyInstances instances = new TallyInstances();

    @AfterEach
    void stop() throws SQLException {
        instances.close();
        sql("DELETE FROM offers.offer_kind WHERE id = 'mystery'");
    }

    void sql(String statement) throws SQLException {
        try (Connection c = DriverManager.getConnection(instances.url(),
                AbstractIntegrationTest.POSTGRES.getUsername(), AbstractIntegrationTest.POSTGRES.getPassword())) {
            c.createStatement().execute(statement);
        }
    }

    @Test
    void refusesToStartWhileAnApprovedKindHasNoRule() throws SQLException {
        // Starts on this test's own database, and shows the seeded kinds all have rules.
        instances.stop(instances.start("startup-check-before", NothingExtra.class));
        sql("INSERT INTO offers.offer_kind (id, status) VALUES ('mystery', 'APPROVED')");

        Throwable refused = catchThrowable(() -> instances.start("startup-check-refused", NothingExtra.class));

        Throwable cause = NestedExceptionUtils.getMostSpecificCause(refused);
        System.out.println("STARTUP-CHECK " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        assertThat(cause).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mystery")
                .hasMessageContaining("no pricing rule");

        // An experimental kind needs no rule yet.
        sql("UPDATE offers.offer_kind SET status = 'EXPERIMENTAL' WHERE id = 'mystery'");
        instances.stop(instances.start("startup-check-experimental", NothingExtra.class));
    }
}
