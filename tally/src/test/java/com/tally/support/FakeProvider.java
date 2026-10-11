package com.tally.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The fake provider as a container, built from the jar the reactor built first, so Tally's calls
 * cross a real network (FR-020). One container per test run.
 */
public final class FakeProvider {

    private static final Path MODULE = Path.of("..", "fake-provider").toAbsolutePath().normalize();

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
            new ImageFromDockerfile("tally-fake-provider-test", false)
                    .withFileFromPath("Dockerfile", MODULE.resolve("Dockerfile"))
                    .withFileFromPath("target/fake-provider-test.jar", jar()))
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/admin/charges/count?reference=startup").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private FakeProvider() {
    }

    public static synchronized String baseUrl() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8081);
    }

    private static RestClient http() {
        return RestClient.create(baseUrl());
    }

    /** A token, as the hosted card form would give the customer's browser (FR-015). */
    public static String token() {
        return http().post().uri("/tokens").retrieve()
                .body(new ParameterizedTypeReference<Map<String, String>>() { }).get("token");
    }

    /** Sets the provider's modes, for example {@code mode("OUTAGE")} or {@code mode("SLOW(200)", "LIE")}. */
    public static void mode(String... modes) {
        http().post().uri("/admin/mode").body(Map.of("modes", List.of(modes))).retrieve().toBodilessEntity();
    }

    /** How many charge requests reached the provider for a reference, including those it refused. */
    public static int requestCount(String reference) {
        return http().get().uri("/admin/requests/count?reference={r}", reference).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() { }).get("count");
    }

    /** Totals since the provider started: requests, rateLimited (429), unavailable (503), charged. */
    public static Map<String, Long> stats() {
        return http().get().uri("/admin/stats").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Long>>() { });
    }

    /**
     * Charges the provider directly, as a Tally worker's call would: for tests that leave Tally as a crash
     * during that call would (AT-15). Returns the provider's answer.
     */
    public static Map<String, Object> charge(String token, long amountMinor, String currency, String reference) {
        return http().post().uri("/charges")
                .body(Map.of("token", token, "amount", amountMinor, "currency", currency, "reference", reference))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    /** As {@link #charge}, with an {@code Idempotency-Key} (CONTRACT.md A22). */
    public static Map<String, Object> charge(String token, long amountMinor, String currency, String reference,
            String key) {
        return http().post().uri("/charges").header("Idempotency-Key", key)
                .body(Map.of("token", token, "amount", amountMinor, "currency", currency, "reference", reference))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    /** Test-only: as if the provider's key retention (24 hours) had passed for every key (A22). */
    public static void pruneIdempotencyKeys() {
        http().post().uri("/admin/idempotency-keys/prune").retrieve().toBodilessEntity();
    }

    /** How many charges the provider recorded for a reference. */
    public static int chargeCount(String reference) {
        return http().get().uri("/admin/charges/count?reference={r}", reference).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() { }).get("count");
    }

    private static Path jar() {
        try (Stream<Path> files = Files.list(MODULE.resolve("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("fake-provider-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Build the fake provider first: ./mvnw verify from the repository root"));
        } catch (IOException e) {
            throw new IllegalStateException("Build the fake provider first: ./mvnw verify from the repository root", e);
        }
    }
}
