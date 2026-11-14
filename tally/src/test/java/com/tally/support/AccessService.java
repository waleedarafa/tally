package com.tally.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access, the second deployable (ADR-001), as billing's acceptance tests meet it (Slice 2 PR-7): containers built
 * from {@code access/Dockerfile} and the jar the reactor built, reading the event log over the network
 * ({@link EventLog#network()}), with Access's own PostgreSQL (ADR-007), a container of its own on the same network.
 * One object is one Access deployment: its own database and consumer group, on one topic; its instances share both.
 * Each instance's output goes to {@code tally/target/instances/<name>.log}.
 */
public final class AccessService implements AutoCloseable {

    private static final Path MODULE = Path.of("..", "access").toAbsolutePath().normalize();
    private static final String DATABASE_HOST = "access-postgres";
    private static final int PORT = 8090;

    private static PostgreSQLContainer database;
    private static ImageFromDockerfile image;

    private final String topic;
    private final String databaseName;
    private final String group;
    private final JdbcTemplate jdbc;
    private final List<ContainerInstance> running = new ArrayList<>();

    private AccessService(String topic, String databaseName) {
        this.topic = topic;
        this.databaseName = databaseName;
        this.group = "tally-access-" + databaseName;
        String url = database().getJdbcUrl().replaceFirst("/" + database().getDatabaseName() + "(\\?|$)",
                "/" + databaseName + "$1");
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(url, database().getUsername(),
                database().getPassword()));
    }

    /** A deployment of Access on the topic, with a database and a consumer group of its own. */
    public static AccessService on(String topic) {
        String name = "access_" + UUID.randomUUID().toString().substring(0, 8);
        new JdbcTemplate(new DriverManagerDataSource(database().getJdbcUrl(), database().getUsername(),
                database().getPassword())).execute("CREATE DATABASE " + name);
        return new AccessService(topic, name);
    }

    private static synchronized PostgreSQLContainer database() {
        if (database == null) {
            database = new PostgreSQLContainer("postgres:17-alpine");
            database.withNetwork(EventLog.network()).withNetworkAliases(DATABASE_HOST);
            database.start();
        }
        return database;
    }

    private static synchronized ImageFromDockerfile image() {
        if (image == null) {
            image = new ImageFromDockerfile("tally-access-test", false)
                    .withFileFromPath("Dockerfile", MODULE.resolve("Dockerfile"))
                    .withFileFromPath("target/tally-access-test.jar", jar());
        }
        return image;
    }

    private static Path jar() {
        try (Stream<Path> files = Files.list(MODULE.resolve("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("tally-access-.*\\.jar"))
                    .findFirst().orElseThrow();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Access's jar is missing: build it first (./mvnw -pl access package"
                    + " -DskipTests, or ./mvnw verify for the whole reactor)", e);
        }
    }

    /** Starts an instance; returns once it is alive (it may not be ready yet). */
    public Instance start(String name) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.datasource.url", "jdbc:postgresql://" + DATABASE_HOST + ":5432/" + databaseName);
        settings.put("spring.datasource.username", database().getUsername());
        settings.put("spring.datasource.password", database().getPassword());
        settings.put("tally.access.log.bootstrap-servers", EventLog.INTERNAL_BOOTSTRAP_SERVERS);
        settings.put("tally.access.log.topic", topic);
        settings.put("tally.access.log.group-id", group);
        settings.put("tally.access.log.retry-after", "200ms");
        Path log = log(name);
        GenericContainer<?> container = new GenericContainer<>(image())
                .withNetwork(EventLog.network())
                .withEnv("SPRING_APPLICATION_JSON", JsonMapper.builder().build().writeValueAsString(settings))
                .withEnv("HOSTNAME", name)
                .withExposedPorts(PORT)
                .withLogConsumer(frame -> append(log, frame))
                .waitingFor(Wait.forHttp("/actuator/health/liveness").forPort(PORT).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        container.start();
        ContainerInstance instance = new ContainerInstance(container);
        running.add(instance);
        return instance;
    }

    private static Path log(String name) {
        try {
            Path dir = Path.of("target", "instances");
            Files.createDirectories(dir);
            Path log = dir.resolve(name + ".log");
            Files.deleteIfExists(log);
            return log;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized void append(Path log, OutputFrame frame) {
        try {
            Files.writeString(log, frame.getUtf8String(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The log is for people reading a failure; losing a line never fails the test.
        }
    }

    /** Stops an instance as the platform does: it is asked to stop (SIGTERM), so it leaves the consumer group. */
    public void stop(Instance instance) {
        ContainerInstance stopping = (ContainerInstance) instance;
        GenericContainer<?> container = stopping.container;
        container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(30).exec();
        container.stop();
        running.remove(stopping);
    }

    /** The rebuild's first step: empties Access's database (facts, positions and the ADR-036 mark), in one statement. */
    public void emptyDatabase() {
        jdbc.execute("TRUNCATE access.facts, access.log_position, access.copy_state");
    }

    /** Access's own database, from the test. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        running.forEach(instance -> instance.container.stop());
        running.clear();
    }

    /** One running Access instance. */
    public interface Instance {

        /** The HTTP status of {@code GET /v1/access/{id}}. */
        int status(UUID subscriberId);

        /** The answer to {@code GET /v1/access/{id}}; fails unless the status is 200. */
        Map<String, Object> answer(UUID subscriberId);

        /** Whether {@code /actuator/health/readiness} says it accepts traffic. */
        boolean ready();
    }

    private static final class ContainerInstance implements Instance {

        private final GenericContainer<?> container;
        private final RestClient http;

        ContainerInstance(GenericContainer<?> container) {
            this.container = container;
            this.http = RestClient.builder()
                    .baseUrl("http://" + container.getHost() + ":" + container.getMappedPort(PORT))
                    .defaultStatusHandler(status -> true, (request, response) -> { })
                    .build();
        }

        @Override
        public int status(UUID subscriberId) {
            return http.get().uri("/v1/access/{id}", subscriberId).retrieve().toBodilessEntity().getStatusCode()
                    .value();
        }

        @Override
        public Map<String, Object> answer(UUID subscriberId) {
            ResponseEntity<Map<String, Object>> response = http.get().uri("/v1/access/{id}", subscriberId).retrieve()
                    .toEntity(new ParameterizedTypeReference<>() { });
            if (response.getStatusCode().value() != 200) {
                throw new IllegalStateException("Access answered " + response.getStatusCode() + " for "
                        + subscriberId);
            }
            return response.getBody();
        }

        @Override
        public boolean ready() {
            return http.get().uri("/actuator/health/readiness").retrieve().toBodilessEntity().getStatusCode()
                    .value() == 200;
        }
    }
}
