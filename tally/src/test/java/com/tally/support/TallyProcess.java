package com.tally.support;

import com.tally.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Tally as a separate operating-system process, started from the jar the build packaged, on the
 * real clock. Killing it is a real SIGKILL: no shutdown hook, nothing finishes (AT-9). Its output
 * goes to tally/target/instances/{name}.log.
 */
public final class TallyProcess implements AutoCloseable {

    private final String name;
    private final Process process;
    private final Path log;

    private TallyProcess(String name, Process process, Path log) {
        this.name = name;
        this.process = process;
        this.log = log;
    }

    /**
     * Starts an instance and waits until it has started. {@code HOSTNAME} is set to {@code name}, so
     * the queue rows it claims show {@code claimed_by = name-…}.
     */
    public static TallyProcess start(String name, String databaseUrl, Map<String, String> settings) {
        try {
            Path dir = Path.of("target", "instances");
            Files.createDirectories(dir);
            Path log = dir.resolve(name + ".log");
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-jar", jar().toString(),
                    "--spring.datasource.url=" + databaseUrl,
                    "--spring.datasource.username=" + AbstractIntegrationTest.POSTGRES.getUsername(),
                    "--spring.datasource.password=" + AbstractIntegrationTest.POSTGRES.getPassword(),
                    "--server.port=0"));
            settings.forEach((k, v) -> command.add("--" + k + "=" + v));
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("HOSTNAME", name);
            TallyProcess started = new TallyProcess(name, builder.start(), log);
            started.awaitStarted(Duration.ofMinutes(2));
            return started;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start " + name, e);
        }
    }

    /** SIGKILL: the process ends at once, with no shutdown hook and no work finished. */
    public void kill() {
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String name() {
        return name;
    }

    public Path log() {
        return log;
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitStarted(Duration atMost) throws IOException {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains("Started TallyApplication")) {
                return;
            }
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited during startup:\n" + tail());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException(name + " did not start within " + atMost + ":\n" + tail());
    }

    private String tail() throws IOException {
        List<String> lines = Files.readAllLines(log);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private static Path jar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("tally-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No tally jar in target/: run ./mvnw verify"));
        }
    }
}
