package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.platform.IdempotencyKeys;
import com.tally.platform.IdempotencyStore;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * A sign-up request that dies after its business change and before its response is written (PR #23 review).
 * The response is stored in the same transaction as the sign-up, so the sign-up rolls back with it; the key
 * stays claimed, and after the request timeout (60 s) the next request with it takes it over and acts again.
 * The retry gets 201: not "still being handled", and not "already subscribed".
 *
 * <p>The death is simulated where the response is stored: an {@link Error} thrown there unwinds the request
 * like a killed process would, without the release a handled failure does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SignUpRecoveryIT.Killer.class)
class SignUpRecoveryIT extends AbstractIntegrationTest {

    /** Keys whose request dies when it stores its response. */
    static final Set<String> DIE_ON = ConcurrentHashMap.newKeySet();

    static final class RequestDied extends Error {
        RequestDied() {
            super("Simulated: the request died before its response was written");
        }
    }

    @TestConfiguration
    static class Killer {

        @Bean
        static BeanPostProcessor dieWhenStoring() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof IdempotencyKeys real)) {
                        return bean;
                    }
                    return (IdempotencyKeys) table -> dying(real.register(table));
                }
            };
        }

        static IdempotencyStore dying(IdempotencyStore real) {
            return new IdempotencyStore() {
                @Override
                public Claim claim(String scope, String key, String requestHash) {
                    return real.claim(scope, key, requestHash);
                }

                @Override
                public void complete(String scope, String key, StoredResponse response) {
                    if (DIE_ON.remove(key)) {
                        throw new RequestDied();
                    }
                    real.complete(scope, key, response);
                }

                @Override
                public void release(String scope, String key) {
                    real.release(scope, key);
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountApi accounts;

    @Autowired
    JdbcTemplate jdbc;

    RestClient tally;

    @BeforeEach
    void client() {
        tally = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    ResponseEntity<String> signUp(String key, UUID account) {
        return tally.post().uri("/v1/subscriptions").header("Idempotency-Key", key)
                .body(Map.of("accountId", account, "planId", "BASIC", "paymentToken", "tok_" + account))
                .retrieve().toEntity(String.class);
    }

    long subscriptions(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?", Long.class,
                account);
    }

    @Test
    void aRequestThatDiesBeforeItsResponseIsWrittenIsTakenOverAfterTheTimeoutAndGets201() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();
        DIE_ON.add(key);

        ResponseEntity<String> died = signUp(key, account);

        assertThat(died.getStatusCode().is5xxServerError()).isTrue();
        assertThat(subscriptions(account)).as("the sign-up rolled back with its response").isZero();
        // Within the request timeout, the key is still held by the request that died.
        assertThat(signUp(key, account).getStatusCode().value()).isEqualTo(409);

        clock.advance(Duration.ofSeconds(61));
        ResponseEntity<String> retry = signUp(key, account);

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(subscriptions(account)).isEqualTo(1);
        // From now on the key replays that 201.
        ResponseEntity<String> again = signUp(key, account);
        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getBody()).isEqualTo(retry.getBody());
    }
}
