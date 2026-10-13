package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * A0: {@code /actuator/prometheus} serves every {@code tally.*} metric, so alert rules can read them
 * (Slice 2 step 0 brief, point 1). A metric's Prometheus name is its Micrometer name with dots as
 * underscores; a counter's also ends in {@code _total}, so a name is matched as a prefix of the TYPE line's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusEndpointIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    @Test
    void everyTallyMetricIsServedInPrometheusFormat() {
        String scrape = RestClient.create("http://localhost:" + port).get().uri("/actuator/prometheus")
                .retrieve().body(String.class);
        List<String> types = scrape.lines().filter(line -> line.startsWith("# TYPE ")).toList();

        List<String> tally = meters.getMeters().stream().map(Meter::getId).map(Meter.Id::getName)
                .filter(name -> name.startsWith("tally.")).distinct().sorted().toList();
        assertThat(tally).isNotEmpty();
        assertThat(tally).allSatisfy(name -> assertThat(types)
                .as("TYPE line for %s", name)
                .anyMatch(line -> line.startsWith("# TYPE " + name.replace('.', '_'))));
        // The two gauges this step adds (A1, A1b), by the exact names the alert rules use.
        assertThat(types).contains("# TYPE tally_payment_unknown_oldest_age_seconds gauge",
                "# TYPE tally_queue_last_progress_age_seconds gauge");
    }
}
