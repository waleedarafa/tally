package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

/**
 * F14, ADR-030: the alert rules, their runbooks, their unit tests and the metrics Tally exports agree. An alert on
 * a renamed metric never fires, and nothing else shows it; so the rules' metric names are compared with a scrape of
 * a running Tally.
 *
 * <p>Fails when:
 * <ol>
 *   <li>a rule has no {@code severity} ({@code page} or {@code ticket}), {@code driver}, {@code summary} or
 *       {@code runbook_url};</li>
 *   <li>a {@code runbook_url} names a file that does not exist in {@code docs/runbooks/};</li>
 *   <li>a runbook in {@code docs/runbooks/} is named by no rule;</li>
 *   <li>a rule reads a {@code tally_} metric that Tally does not export;</li>
 *   <li>a {@code page} alert has no unit test where it fires and none where it stays quiet.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class F14AlertRulesAgreeIT extends AbstractIntegrationTest {

    static final Path RULES = Path.of("../ops/alerts/tally.rules.yml");
    static final Path RULE_TESTS = Path.of("../ops/alerts/tally.rules.test.yml");
    static final Path RUNBOOKS = Path.of("../docs/runbooks");
    static final String RUNBOOK_URL = "https://github.com/waleedarafa/tally/blob/main/docs/runbooks/";
    static final Pattern TALLY_METRIC = Pattern.compile("\\btally_[a-z0-9_]+\\b");

    @LocalServerPort
    int port;

    List<Map<String, Object>> rules;

    @BeforeEach
    void readRules() throws IOException {
        rules = rules(RULES);
    }

    @Test
    void everyRuleNamesItsSeverityDriverSummaryAndRunbook() {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Map<String, Object> labels = map(rule.get("labels"));
            Map<String, Object> annotations = map(rule.get("annotations"));
            if (!List.of("page", "ticket").contains(String.valueOf(labels.get("severity")))) {
                missing.add(rule.get("alert") + ": severity is " + labels.get("severity") + ", not page or ticket");
            }
            for (String label : List.of("driver")) {
                if (blank(labels.get(label))) {
                    missing.add(rule.get("alert") + ": no label " + label);
                }
            }
            for (String annotation : List.of("summary", "runbook_url")) {
                if (blank(annotations.get(annotation))) {
                    missing.add(rule.get("alert") + ": no annotation " + annotation);
                }
            }
        }
        assertThat(missing).as("Rules without a severity, driver, summary or runbook (ADR-030)").isEmpty();
    }

    @Test
    void everyRunbookUrlNamesARunbookThatExists() {
        List<String> broken = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String url = String.valueOf(map(rule.get("annotations")).get("runbook_url"));
            if (!url.startsWith(RUNBOOK_URL) || !Files.isRegularFile(RUNBOOKS.resolve(url.substring(RUNBOOK_URL.length())))) {
                broken.add(rule.get("alert") + ": " + url);
            }
        }
        assertThat(broken).as("runbook_url must name a file in docs/runbooks/ under " + RUNBOOK_URL).isEmpty();
    }

    @Test
    void everyRunbookIsNamedByARule() throws IOException {
        Set<String> named = rules.stream().map(rule -> String.valueOf(map(rule.get("annotations")).get("runbook_url")))
                .filter(url -> url.startsWith(RUNBOOK_URL)).map(url -> url.substring(RUNBOOK_URL.length()))
                .collect(Collectors.toSet());
        try (Stream<Path> files = Files.list(RUNBOOKS)) {
            List<String> unnamed = files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".md"))
                    .filter(name -> !named.contains(name)).sorted().toList();
            assertThat(unnamed).as("Runbooks no alert rule names").isEmpty();
        }
    }

    @Test
    void everyMetricARuleReadsIsExported() {
        String scrape = RestClient.create("http://localhost:" + port).get().uri("/actuator/prometheus")
                .retrieve().body(String.class);
        Set<String> exported = scrape.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("[{ ]", 2)[0]).collect(Collectors.toSet());
        List<String> unexported = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            TALLY_METRIC.matcher(String.valueOf(rule.get("expr"))).results().map(m -> m.group())
                    .filter(metric -> !exported.contains(metric)).distinct()
                    .forEach(metric -> unexported.add(rule.get("alert") + ": " + metric));
        }
        assertThat(unexported).as("Metrics the rules read that /actuator/prometheus does not serve").isEmpty();
    }

    @Test
    void everyPageAlertHasAFiringAndAQuietTest() throws IOException {
        Map<String, Object> tests = new Yaml().load(Files.readString(RULE_TESTS));
        Set<String> fires = new TreeSet<>();
        Set<String> quiet = new TreeSet<>();
        for (Object group : list(tests.get("tests"))) {
            for (Object check : list(map(group).get("alert_rule_test"))) {
                String alert = String.valueOf(map(check).get("alertname"));
                (list(map(check).get("exp_alerts")).isEmpty() ? quiet : fires).add(alert);
            }
        }
        List<String> untested = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String alert = String.valueOf(rule.get("alert"));
            if ("page".equals(map(rule.get("labels")).get("severity"))) {
                if (!fires.contains(alert)) {
                    untested.add(alert + ": no test where it fires");
                }
                if (!quiet.contains(alert)) {
                    untested.add(alert + ": no test where it stays quiet");
                }
            }
        }
        assertThat(untested).as("Page alerts without unit tests in " + RULE_TESTS.getFileName()).isEmpty();
    }

    static List<Map<String, Object>> rules(Path file) throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(file));
        return list(document.get("groups")).stream().flatMap(group -> list(map(group).get("rules")).stream())
                .map(F14AlertRulesAgreeIT::map).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static List<Object> list(Object value) {
        return value instanceof Collection<?> collection ? List.copyOf(collection) : List.of();
    }

    static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
