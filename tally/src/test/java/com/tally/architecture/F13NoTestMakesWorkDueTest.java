package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * F13, ADR-029 (proposed): a test that waits on background work moves its clock to the work's due time; it never
 * rewrites a job or queue row's due time or status to make the work due. Every test class has a database of its own,
 * so no row is stranded on another context's clock, and a rewrite would only hide a test that does not move time.
 * Only the platform's own tests, which test the queue itself, may write those columns; a test that needs a queue
 * fault (a redelivery after a crash) asks the platform for it ({@code com.tally.platform.internal.QueueFaults}).
 * There are no exceptions.
 *
 * <p>The job and queue tables are read from the main sources: every {@code QueueDefinition.of(name, table, ...)} and
 * every module's {@code *.jobs} table. A test's SQL is read with its string literals joined across {@code +}.
 */
class F13NoTestMakesWorkDueTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path TESTS = Path.of("src/test/java");

    static final Pattern QUEUE_DEFINITION = Pattern.compile("QueueDefinition\\.of\\(\\s*\"[^\"]+\",\\s*\"([a-z_]+\\.[a-z_]+)\"");
    static final Pattern JOB_TABLE = Pattern.compile("\"([a-z_]+\\.jobs)\"");
    static final Pattern UPDATE = Pattern.compile("UPDATE\\s+([a-z_]+\\.[a-z_]+)\\s+SET\\s+([^\"]*?)(?:\\s+WHERE\\b|\"|$)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern MAKES_DUE = Pattern.compile("\\b(due_at|status)\\s*=", Pattern.CASE_INSENSITIVE);
    static final Pattern CONCATENATION = Pattern.compile("\"\\s*\\+\\s*\"");

    static Set<String> jobAndQueueTables() throws IOException {
        Set<String> tables = new TreeSet<>();
        for (Path file : javaFiles(MAIN)) {
            String source = Files.readString(file);
            QUEUE_DEFINITION.matcher(source).results().forEach(m -> tables.add(m.group(1)));
            JOB_TABLE.matcher(source).results().forEach(m -> tables.add(m.group(1)));
        }
        return tables;
    }

    static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void theJobAndQueueTablesAreFound() throws IOException {
        assertThat(jobAndQueueTables()).contains("payment.jobs", "invoicing.jobs", "platform.jobs",
                "payment.collection_queue", "collections.retry_queue", "subscription.renewal");
    }

    @Test
    void noTestOutsideThePlatformRewritesADueTimeOrStatus() throws IOException {
        Set<String> tables = jobAndQueueTables();
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(TESTS)) {
            String relative = TESTS.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith("com/tally/platform/") || relative.endsWith("F13NoTestMakesWorkDueTest.java")) {
                continue;
            }
            String sql = CONCATENATION.matcher(Files.readString(file)).replaceAll("");
            Matcher update = UPDATE.matcher(sql);
            while (update.find()) {
                String table = update.group(1).toLowerCase();
                if (tables.contains(table) && MAKES_DUE.matcher(update.group(2)).find()) {
                    violations.add(relative + ": UPDATE " + table + " SET " + update.group(2).strip());
                }
            }
        }
        assertThat(violations)
                .as("Tests that rewrite a job or queue row's due time or status: move the test's clock instead (ADR-029)")
                .isEmpty();
    }
}
