package com.tally.support;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A billing database in a PostgreSQL container of its own, for a test that stops the database itself (AT-18): the
 * shared container serves the rest of the run. Stopped and started again with its data, as a database server is
 * ({@code docker stop}, {@code docker start}). Its host port is fixed when it is created, so its URL stays the same
 * across a restart.
 */
public final class BillingDatabase implements AutoCloseable {

    private final PostgreSQLContainer container;
    private final int hostPort;

    private BillingDatabase(PostgreSQLContainer container, int hostPort) {
        this.container = container;
        this.hostPort = hostPort;
    }

    public static BillingDatabase start(String name) {
        int hostPort = freePort();
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
        container.withDatabaseName(name + "_" + UUID.randomUUID().toString().substring(0, 8));
        container.setPortBindings(List.of(hostPort + ":5432"));
        container.start();
        return new BillingDatabase(container, hostPort);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JDBC URL; the same after a restart. */
    public String url() {
        return "jdbc:postgresql://" + container.getHost() + ":" + hostPort + "/" + container.getDatabaseName();
    }

    public String username() {
        return container.getUsername();
    }

    public String password() {
        return container.getPassword();
    }

    /** The access feed's settings for billing on this database: the broker, and a topic of its own. */
    public Map<String, String> feedSettings() {
        return EventLog.feedSettings(url());
    }

    /** Stops the server; its data stays. */
    public void stop() {
        container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(30).exec();
    }

    /** Starts the stopped server again, and waits until it accepts connections. */
    public void start() {
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().until(() -> {
            try (Connection connection = connect()) {
                return connection.isValid(2);
            }
        });
    }

    /** A new connection; fails while the server is stopped. */
    public Connection connect() throws Exception {
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(url(), username(), password());
    }

    @Override
    public void close() {
        container.stop();
    }
}
