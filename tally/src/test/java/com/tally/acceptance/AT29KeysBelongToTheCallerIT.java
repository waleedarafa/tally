package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.support.TestAccounts;
import com.tally.support.TestTokens;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * AT-29. Keys belong to the caller (Slice 4 brief; ADR-027; ADR-032). Users A and B send the same key value; each gets
 * the result of the user's own request. For "open an account", whose keys shared one scope until this pull request,
 * user B who sends user A's key gets B's own account, never A's (review of #89).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AT29KeysBelongToTheCallerIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestAccounts testAccounts;

    RestClient as(TestTokens.Household household) {
        return household.client(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }));
    }

    ResponseEntity<Map<String, Object>> open(TestTokens.Household household, String key) {
        return as(household).post().uri("/v1/accounts").header("Idempotency-Key", key)
                .body(Map.of("currency", "USD", "timeZone", "UTC"))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    ResponseEntity<Map<String, Object>> signUp(TestTokens.Household household, UUID account, String key) {
        return as(household).post().uri("/v1/subscriptions").header("Idempotency-Key", key)
                .body(Map.of("accountId", account, "planId", "BASIC", "paymentToken", "tok_" + account))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    Object myAccount(TestTokens.Household household) {
        return as(household).get().uri("/v1/me/account").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("id");
    }

    @Test
    void userBWhoSendsUserAsKeyToOpenAnAccountGetsBsOwnAccount() {
        TestTokens.Household a = TestTokens.household();
        TestTokens.Household b = TestTokens.household();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map<String, Object>> forA = open(a, key);
        ResponseEntity<Map<String, Object>> forB = open(b, key);

        assertThat(forA.getStatusCode().value()).isEqualTo(201);
        assertThat(forB.getStatusCode().value()).as("B's own request, not A's stored answer").isEqualTo(201);
        assertThat(forB.getBody().get("id")).as("never A's account").isNotEqualTo(forA.getBody().get("id"));
        assertThat(myAccount(a)).isEqualTo(forA.getBody().get("id"));
        assertThat(myAccount(b)).isEqualTo(forB.getBody().get("id"));
        // The same request again with the same key replays each caller's own answer.
        assertThat(open(b, key).getBody()).isEqualTo(forB.getBody());
        assertThat(open(a, key).getBody()).isEqualTo(forA.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account.idempotency_key WHERE key = ?", Long.class, key))
                .as("one key row for each caller").isEqualTo(2);
    }

    @Test
    void twoUsersSigningUpWithOneKeyValueEachGetTheirOwnSubscription() {
        TestTokens.Household a = TestTokens.household();
        TestTokens.Household b = TestTokens.household();
        UUID accountOfA = testAccounts.openFor(a, Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        UUID accountOfB = testAccounts.openFor(b, Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map<String, Object>> forA = signUp(a, accountOfA, key);
        ResponseEntity<Map<String, Object>> forB = signUp(b, accountOfB, key);

        assertThat(forA.getStatusCode().value()).isEqualTo(201);
        assertThat(forB.getStatusCode().value()).isEqualTo(201);
        assertThat(forB.getBody().get("subscriptionId")).isNotEqualTo(forA.getBody().get("subscriptionId"));
        assertThat(jdbc.queryForList("SELECT scope FROM subscription.idempotency_key WHERE key = ?", String.class,
                key)).as("each key is stored under its caller").containsExactlyInAnyOrder("user:" + a.user(),
                "user:" + b.user());
    }
}
