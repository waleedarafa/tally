package com.fakeprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClock.Configuration.class)
class ProviderControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestClock clock;

    RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
        mode("NORMAL");
    }

    void mode(String... modes) {
        http.post().uri("/admin/mode").body(Map.of("modes", List.of(modes))).retrieve().toBodilessEntity();
    }

    HttpStatusCode chargeStatus(String token, String reference) {
        return http.post().uri("/charges")
                .body(Map.of("token", token, "amount", 999, "currency", "USD", "reference", reference))
                .exchange((request, response) -> response.getStatusCode());
    }

    int requests(String reference) {
        return http.get().uri("/admin/requests/count?reference={r}", reference).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() { }).get("count");
    }

    String token() {
        return http.post().uri("/tokens").retrieve()
                .body(new ParameterizedTypeReference<Map<String, String>>() { }).get("token");
    }

    Map<String, Object> charge(String token, String reference) {
        return http.post().uri("/charges")
                .body(Map.of("token", token, "amount", 999, "currency", "USD", "reference", reference))
                .retrieve().body(new ParameterizedTypeReference<>() { });
    }

    int count(String reference) {
        return http.get().uri("/admin/charges/count?reference={r}", reference).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() { }).get("count");
    }

    @Test
    void chargesAKnownTokenAndRecordsTheTruth() {
        Map<String, Object> response = charge(token(), "INV-1");

        assertThat(response).containsEntry("outcome", "SUCCEEDED").containsEntry("reference", "INV-1");
        List<Map<String, Object>> recorded = http.get().uri("/charges?reference=INV-1").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c).containsEntry("chargeId", response.get("chargeId"));
            assertThat(c).containsEntry("outcome", "SUCCEEDED");
            assertThat(c).containsEntry("amount", 999);
        });
        assertThat(count("INV-1")).isEqualTo(1);
    }

    @Test
    void anOutageAnswers503AndChargesNothing() {
        mode("OUTAGE");

        assertThat(chargeStatus(token(), "OUT-1").value()).isEqualTo(503);
        assertThat(count("OUT-1")).isZero();
        assertThat(requests("OUT-1")).isEqualTo(1);
    }

    @Test
    void aRateLimitAnswers429AboveTheLimit() {
        mode("RATE_LIMIT(3)");
        String token = token();
        clock.nextSecond();

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            statuses.add(chargeStatus(token, "RL-" + i).value());
        }

        assertThat(statuses).containsExactly(200, 200, 200, 429, 429, 429, 429, 429, 429, 429);
        Map<String, Integer> stats = http.get().uri("/admin/stats").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(stats.get("rateLimited")).isGreaterThanOrEqualTo(7);
    }

    @Test
    void aLyingProviderReportsTheOppositeOfWhatItRecorded() {
        mode("LIE");

        assertThat(charge(token(), "LIE-1")).containsEntry("outcome", "DECLINED_HARD");
        assertThat(charge("tok_unknown", "LIE-2")).containsEntry("outcome", "SUCCEEDED");
        List<Map<String, Object>> truth = http.get().uri("/charges?reference=LIE-1").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        assertThat(truth).singleElement().satisfies(c -> assertThat(c).containsEntry("outcome", "SUCCEEDED"));
    }

    @Test
    void declinesFollowTheirPercentages() {
        mode("DECLINE_SOFT(100)");
        assertThat(charge(token(), "SOFT-1")).containsEntry("outcome", "DECLINED_SOFT");
        mode("DECLINE_HARD(100)");
        assertThat(charge(token(), "HARD-1")).containsEntry("outcome", "DECLINED_HARD");
    }

    @Test
    void timeoutAfterChargeRecordsTheChargeThenAnswersTooLate() {
        mode("TIMEOUT_AFTER_CHARGE");
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(1));
        RestClient impatient = RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(factory).build();

        assertThatThrownBy(() -> impatient.post().uri("/charges")
                        .body(Map.of("token", token(), "amount", 999, "currency", "USD", "reference", "TAC-1"))
                        .retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat(count("TAC-1")).isEqualTo(1);
    }

    @Test
    void anUnknownTokenIsAHardDecline() {
        assertThat(charge("tok_unknown", "INV-2")).containsEntry("outcome", "DECLINED_HARD");
        assertThat(count("INV-2")).isEqualTo(1);
        assertThat(count("INV-none")).isZero();
    }
}
