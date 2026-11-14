package com.tally.accessfeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.support.EventLog;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The AT-1 time through the log (ADR-014: 5 seconds from sign-up to access), as far as PR-5 goes: from
 * {@code POST /v1/subscriptions} answering 201 to the account's facts committed in the event log, read as Access
 * reads it (committed only). Each send is a transaction of its own (decision on PR-5), so this measures what the
 * transactions cost. Access's own hop comes with PR-6 and AT-1 itself with PR-7. Prints AT1-FEED lines.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"tally.platform.polling-enabled=true", "tally.platform.poll-interval=100ms"})
class AccessFeedLatencyIT extends AbstractIntegrationTest {

    static final Duration AT_1 = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Value("${tally.accessfeed.log.topic}")
    String topic;

    @Autowired
    AccountApi accounts;

    @Test
    void aSignUpsFactsAreCommittedInTheLogWellWithinAt1sFiveSeconds() {
        List<Duration> times = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
            long start = System.nanoTime();
            int status = RestClient.create("http://localhost:" + port).post().uri("/v1/subscriptions")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", "tok_latency_" + i))
                    .retrieve().toBodilessEntity().getStatusCode().value();
            assertThat(status).isEqualTo(201);
            await().atMost(AT_1).pollInterval(Duration.ofMillis(50)).until(() -> EventLog.readCommitted(topic)
                    .stream().anyMatch(r -> r.key().equals(account.toString())));
            times.add(Duration.ofNanos(System.nanoTime() - start));
        }
        System.out.println("AT1-FEED sign-up to committed facts in the log, ms: "
                + times.stream().map(Duration::toMillis).toList());
        assertThat(times).allSatisfy(time -> assertThat(time).isLessThan(AT_1));
    }
}
