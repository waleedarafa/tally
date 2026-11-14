package com.tallyaccess.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tallyaccess.AccessApplication;
import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.application.AccessCopy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/**
 * ADR-036: a "complete once" mark in Access's database. An
 * instance whose database holds a copy that once reached the end of the log answers from it at once, also when it
 * starts while the log is down (D13). An emptied database has no mark: it waits for the catch-up, as a rebuild must.
 * Instances are started by hand on one database, as a restart would.
 */
class AccessStartWithoutLogIT {

    /** No broker listens here: the log is down. */
    static final String LOG_DOWN = "localhost:1";

    final List<ConfigurableApplicationContext> started = new ArrayList<>();

    @AfterEach
    void stop() {
        started.forEach(ConfigurableApplicationContext::close);
    }

    ConfigurableApplicationContext start(Map<String, String> settings) {
        Map<String, String> all = new LinkedHashMap<>(settings);
        all.put("server.port", "0");
        all.put("tally.access.log.retry-after", "200ms");
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AccessApplication.class)
                .run(all.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        started.add(context);
        return context;
    }

    static int status(ConfigurableApplicationContext instance, UUID account) {
        return get(instance, "/v1/access/" + account);
    }

    /** The status of the readiness probe the platform asks. */
    static int probe(ConfigurableApplicationContext instance) {
        return get(instance, "/actuator/health/readiness");
    }

    static int get(ConfigurableApplicationContext instance, String path) {
        String port = instance.getEnvironment().getProperty("local.server.port");
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(code -> true, (request, response) -> { }).build()
                .get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void aCopyThatWasCompleteAnswersAtOnceWhenAnInstanceStartsWhileTheLogIsDown() {
        Map<String, String> settings = AccessTestSupport.isolated();
        UUID account = UUID.randomUUID();
        try (KafkaProducer<String, String> producer = AccessTestSupport.producer()) {
            AccessTestSupport.sendCommitted(producer, settings.get("tally.access.log.topic"), account,
                    AccessTestSupport.facts(account, 1, "GOOD", "ACTIVE"));
        }
        ConfigurableApplicationContext first = start(settings);
        await().atMost(Duration.ofSeconds(30)).until(() -> first.getBean(AccessCopy.class).answer(account)
                .sequence() != null && status(first, account) == 200);
        first.close();

        Map<String, String> logDown = new LinkedHashMap<>(settings);
        logDown.put("tally.access.log.bootstrap-servers", LOG_DOWN);
        ConfigurableApplicationContext restarted = start(logDown);

        await().atMost(Duration.ofSeconds(10)).until(() -> status(restarted, account) == 200);
        assertThat(probe(restarted)).isEqualTo(200);
        assertThat(restarted.getBean(AccessCopy.class).answer(account).canWatch()).isTrue();
    }

    @Test
    void anEmptiedDatabaseIsNotReadyWhileTheLogIsDown() throws InterruptedException {
        Map<String, String> settings = AccessTestSupport.isolated();
        ConfigurableApplicationContext first = start(settings);
        await().atMost(Duration.ofSeconds(30)).until(() -> status(first, UUID.randomUUID()) == 200);
        first.close();
        // The rebuild's first step: the database is emptied, the mark with it.
        new JdbcTemplate(new DriverManagerDataSource(settings.get("spring.datasource.url"),
                settings.get("spring.datasource.username"), settings.get("spring.datasource.password"))).update("TRUNCATE access.facts, access.log_position, access.copy_state");

        Map<String, String> logDown = new LinkedHashMap<>(settings);
        logDown.put("tally.access.log.bootstrap-servers", LOG_DOWN);
        ConfigurableApplicationContext rebuilding = start(logDown);

        Thread.sleep(3000);
        assertThat(status(rebuilding, UUID.randomUUID())).as("no answer from a copy that was never complete")
                .isEqualTo(503);
        // Spring Boot reports "accepting traffic" once the application has started; the probe must still say no
        // (found by AT-19 in PR-7).
        assertThat(probe(rebuilding)).as("the readiness probe of a copy that was never complete").isEqualTo(503);
    }
}
