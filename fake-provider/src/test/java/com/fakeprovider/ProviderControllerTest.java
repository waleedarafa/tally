package com.fakeprovider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderControllerTest {

    @LocalServerPort
    int port;

    RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
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
    void anUnknownTokenIsAHardDecline() {
        assertThat(charge("tok_unknown", "INV-2")).containsEntry("outcome", "DECLINED_HARD");
        assertThat(count("INV-2")).isEqualTo(1);
        assertThat(count("INV-none")).isZero();
    }
}
