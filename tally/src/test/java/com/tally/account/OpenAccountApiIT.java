package com.tally.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * {@code POST /v1/accounts} changes state, so it requires an Idempotency-Key (CLAUDE.md rule 13, ADR-019),
 * handled by the platform's shared mechanism (Slice 1R PR-9b).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAccountApiIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    RestClient tally;

    @BeforeEach
    void client() {
        tally = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    ResponseEntity<String> open(String key, String currency) {
        var request = tally.post().uri("/v1/accounts");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.body(Map.of("currency", currency, "timeZone", "Europe/London")).retrieve()
                .toEntity(String.class);
    }

    static void assertProblem(ResponseEntity<String> response, int status, String type) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).contains("\"type\":\"" + type + "\"");
    }

    long accountsWithBody(String body) {
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        return jdbc.queryForObject("SELECT count(*) FROM account.account WHERE id = ?::uuid", Long.class, id);
    }

    @Test
    void anAccountOpenedWithoutAnIdempotencyKeyIsRefused() {
        assertProblem(open(null, "USD"), 400, "about:blank");
    }

    @Test
    void theSameRequestTwiceWithOneKeyOpensOneAccountAndAnswersTheSame() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = open(key, "USD");
        ResponseEntity<String> second = open(key, "USD");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(accountsWithBody(first.getBody())).isEqualTo(1);
    }

    @Test
    void aKeyReusedForADifferentRequestIsRefused() {
        String key = UUID.randomUUID().toString();
        open(key, "USD");

        assertProblem(open(key, "AED"), 422, "/problems/idempotency-key-reused");
    }

    @Test
    void aRefusalIsStoredAndReplayed() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> refused = open(key, "NOT-A-CURRENCY");

        assertProblem(refused, 400, "about:blank");
        assertThat(open(key, "NOT-A-CURRENCY").getBody()).isEqualTo(refused.getBody());
    }

    /**
     * The key's request is still being handled (simulated: its response not yet stored, claimed just now):
     * the same request gets request-in-progress, and Retry-After says when the request timeout (60 s) ends.
     */
    @Test
    void theSameRequestWhileTheFirstIsInFlightIsRequestInProgressWithRetryAfter() {
        String key = UUID.randomUUID().toString();
        open(key, "USD");
        jdbc.update("UPDATE account.idempotency_key SET response_status = NULL, response_content_type = NULL,"
                + " response_body = NULL, claimed_at = ? WHERE key = ?",
                clock.instant().atOffset(java.time.ZoneOffset.UTC), key);

        ResponseEntity<String> again = open(key, "USD");

        assertProblem(again, 409, "/problems/request-in-progress");
        assertThat(again.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
