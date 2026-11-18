package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.tally.AbstractIntegrationTest;
import com.tally.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * The counters that {@code ApiTokensRefused} and {@code IdentityKeysUnavailable} read (ADR-030, Slice 4 PR-7) count
 * what the rules assume: each answer under {@code /v1/} by outcome, a 401 among them, and each fetch of the key set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiMetricsIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    double count(String name, String outcome) {
        return meters.get(name).tag("outcome", outcome).counter().count();
    }

    int status(String authorization) {
        var request = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(code -> true, (r, response) -> { }).build().get().uri("/v1/me/account");
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void eachAnswerUnderV1IsCountedByOutcomeTheRefusalsAmongThem() {
        double answered = count(ApiMetrics.REQUESTS, "answered");
        double refused = count(ApiMetrics.REQUESTS, "unauthenticated");

        assertThat(status(TestTokens.household().bearer())).as("a household with no account yet").isEqualTo(404);
        assertThat(status(null)).as("no token").isEqualTo(401);
        assertThat(status("Bearer " + TestTokens.token().forged().sign())).as("a bad signature").isEqualTo(401);

        assertThat(count(ApiMetrics.REQUESTS, "answered")).isEqualTo(answered + 1);
        assertThat(count(ApiMetrics.REQUESTS, "unauthenticated")).isEqualTo(refused + 2);
        assertThat(count(ApiMetrics.KEY_FETCHES, "success")).as("the key set was fetched to check a token")
                .isPositive();
    }

    @Test
    void aFetchThatFailsIsCountedAsAFailure() throws Exception {
        ApiMetrics.KeyFetchCounter fetches = new ApiMetrics.KeyFetchCounter(new SimpleMeterRegistry(), "fetches");
        RestTemplate client = new RestTemplate();
        client.getInterceptors().add(fetches.interceptor());
        HttpServer down = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        down.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(503, -1);
            }
        });
        down.start();
        String uri = "http://127.0.0.1:" + down.getAddress().getPort() + "/jwks";
        try {
            try {
                client.getForObject(uri, String.class);
            } catch (RestClientException expected) {
                // 503: the provider is down.
            }
        } finally {
            down.stop(0);
        }
        try {
            client.getForObject(uri, String.class);
        } catch (RestClientException expected) {
            // Nothing listens any more: the connection is refused.
        }

        assertThat(fetches.failureCount()).isEqualTo(2);
        assertThat(fetches.successCount()).isZero();
    }

    @Test
    void aServerErrorIsCountedAsFailed() {
        assertThat(ApiMetrics.RequestOutcomes.outcome(500)).isEqualTo("failed");
        assertThat(ApiMetrics.RequestOutcomes.outcome(503)).isEqualTo("failed");
        assertThat(ApiMetrics.RequestOutcomes.outcome(401)).isEqualTo("unauthenticated");
        assertThat(ApiMetrics.RequestOutcomes.outcome(403)).isEqualTo("forbidden");
        assertThat(ApiMetrics.RequestOutcomes.outcome(404)).isEqualTo("answered");
    }
}
