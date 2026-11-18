package com.tallyaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The counters that {@code AccessTokensRefused} and {@code AccessIdentityKeysUnavailable} read (ADR-030, Slice 4
 * PR-7) count what the rules assume: each answer by outcome, and each fetch of the key set, a failed one among them.
 * The hold-off is 0 here, so a token with an unknown key ID makes Access fetch at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tally.access.security.key-refetch-hold-off=0s")
class AccessMetricsIT {

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        AccessTestSupport.isolate(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    /** Access answers 503 until its copy has caught up with the log (readiness); wait for the first answer. */
    @BeforeEach
    void caughtUp() {
        await().atMost(Duration.ofSeconds(30)).until(() -> status(TestTokens.streamingPlatformBearer()) == 200);
    }

    @AfterEach
    void providerBack() {
        TestTokens.providerUp(true);
    }

    double count(String name, String outcome) {
        return meters.get(name).tag("outcome", outcome).counter().count();
    }

    int status(String authorization) {
        var request = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(code -> true, (r, response) -> { }).build()
                .get().uri("/v1/access/{id}", UUID.randomUUID());
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void eachAnswerIsCountedByOutcomeAndEachFetchOfTheKeySet() {
        double answered = count(AccessMetrics.REQUESTS, "answered");
        double refused = count(AccessMetrics.REQUESTS, "unauthenticated");
        double forbidden = count(AccessMetrics.REQUESTS, "forbidden");

        assertThat(status(TestTokens.streamingPlatformBearer())).isEqualTo(200);
        assertThat(status(null)).isEqualTo(401);
        assertThat(status(TestTokens.household().bearer())).isEqualTo(403);

        assertThat(count(AccessMetrics.REQUESTS, "answered")).isEqualTo(answered + 1);
        assertThat(count(AccessMetrics.REQUESTS, "unauthenticated")).isEqualTo(refused + 1);
        assertThat(count(AccessMetrics.REQUESTS, "forbidden")).isEqualTo(forbidden + 1);
        assertThat(count(AccessMetrics.KEY_FETCHES, "success")).as("the key set was fetched to check a token")
                .isPositive();
    }

    @Test
    void aFetchThatFailsIsCountedAsAFailure() {
        assertThat(status(TestTokens.streamingPlatformBearer())).isEqualTo(200);
        double failures = count(AccessMetrics.KEY_FETCHES, "failure");
        TestTokens.providerUp(false);

        assertThat(status(TestTokens.token().unknownKey().bearer())).as("an unknown key ID, the provider down")
                .isEqualTo(401);

        assertThat(count(AccessMetrics.KEY_FETCHES, "failure")).isEqualTo(failures + 1);
    }
}
