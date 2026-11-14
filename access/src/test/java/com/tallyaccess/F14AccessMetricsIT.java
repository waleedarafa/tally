package com.tallyaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

/**
 * F14, Access's side (ADR-030; Slice 2 PR-8): every {@code tally_access_} metric an alert rule reads is one that Access
 * exports. An alert on a renamed metric never fires, and nothing else shows it. Billing's side
 * ({@code F14AlertRulesAgreeIT}) checks every other {@code tally_} metric against a scrape of billing.
 *
 * <p>Fails when a rule reads a {@code tally_access_} metric that {@code /actuator/prometheus} does not serve, or when
 * no rule reads one at all (the rules or this test's prefix moved).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class F14AccessMetricsIT {

    static final Path RULES = Path.of("../ops/alerts/tally.rules.yml");
    static final Pattern ACCESS_METRIC = Pattern.compile("\\btally_access_[a-z0-9_]+\\b");

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        AccessTestSupport.isolate(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void everyAccessMetricARuleReadsIsExported() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(RULES));
        Set<String> read = new TreeSet<>();
        List<String> pairs = new ArrayList<>();
        for (Object group : list(document.get("groups"))) {
            for (Object rule : list(map(group).get("rules"))) {
                ACCESS_METRIC.matcher(String.valueOf(map(rule).get("expr"))).results().map(m -> m.group())
                        .forEach(metric -> {
                            read.add(metric);
                            pairs.add(map(rule).get("alert") + ": " + metric);
                        });
            }
        }
        assertThat(read).as("rules that read Access's metrics").isNotEmpty();

        String scrape = RestClient.create("http://localhost:" + port).get().uri("/actuator/prometheus")
                .retrieve().body(String.class);
        Set<String> exported = scrape.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("[{ ]", 2)[0]).collect(Collectors.toSet());
        assertThat(pairs).as("Access metrics the rules read that Access's /actuator/prometheus does not serve")
                .allMatch(pair -> exported.contains(pair.substring(pair.indexOf(": ") + 2)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static List<Object> list(Object value) {
        return value instanceof Collection<?> collection ? List.copyOf(collection) : List.of();
    }
}
