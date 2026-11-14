package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * AT-20. Blocked and restored (Access rules 2 and 4, design.md §8). {@code AccountStanding.set(BLOCKED)} makes
 * {@code canWatch: false}, reason {@code ACCOUNT_BLOCKED}; {@code GOOD} restores it. Pass when the sequence goes up
 * each time.
 *
 * <p>Billing runs in this test's context, with its pollers on, so the relay sends to the context's own topic; Access is
 * a container reading that topic. The account signs up through the HTTP API, as in AT-1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"tally.platform.polling-enabled=true", "tally.platform.poll-interval=100ms"})
class AT20BlockedAndRestoredIT extends AbstractProviderIntegrationTest {

    @LocalServerPort
    int port;

    @Value("${tally.accessfeed.log.topic}")
    String topic;

    @Autowired
    AccountStanding standing;

    AccessService access;
    Instance instance;

    @BeforeEach
    void startAccess() {
        access = AccessService.on(topic);
        instance = access.start("at20");
    }

    @AfterEach
    void stopAccess() {
        if (access != null) {
            access.close();
        }
    }

    UUID signUp() {
        RestClient tally = RestClient.create("http://localhost:" + port);
        UUID account = UUID.fromString((String) tally.post().uri("/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("currency", "AED", "timeZone", "Asia/Dubai"))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("id"));
        int status = tally.post().uri("/v1/subscriptions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", FakeProvider.token()))
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(201);
        return account;
    }

    Map<String, Object> awaitAnswer(UUID account, Predicate<Map<String, Object>> until) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> instance.status(account) == 200 && until.test(instance.answer(account)));
        return instance.answer(account);
    }

    static long sequence(Map<String, Object> answer) {
        return ((Number) answer.get("sequence")).longValue();
    }

    @Test
    void blockedTakesAccessAwayAndGoodRestoresItWithAHigherSequenceEachTime() {
        UUID account = signUp();
        Map<String, Object> signedUp = awaitAnswer(account, a -> Boolean.TRUE.equals(a.get("canWatch")));
        assertThat(signedUp).containsEntry("reason", "ACTIVE_SOURCE");

        standing.set(account, Standing.BLOCKED, "AT-20");
        Map<String, Object> blocked = awaitAnswer(account, a -> "ACCOUNT_BLOCKED".equals(a.get("reason")));
        assertThat(blocked).containsEntry("canWatch", false);
        assertThat(sequence(blocked)).isGreaterThan(sequence(signedUp));

        standing.set(account, Standing.GOOD, "AT-20, paid");
        Map<String, Object> restored = awaitAnswer(account, a -> Boolean.TRUE.equals(a.get("canWatch")));
        assertThat(restored).containsEntry("reason", "ACTIVE_SOURCE");
        assertThat(sequence(restored)).isGreaterThan(sequence(blocked));

        System.out.printf("AT-20: sequences %d (signed up), %d (blocked), %d (restored)%n", sequence(signedUp),
                sequence(blocked), sequence(restored));
    }
}
