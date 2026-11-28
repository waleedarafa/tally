package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.support.TestAccounts;
import com.tally.support.TestTokens;
import java.sql.Connection;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Slice 3 spike S-4 (WAR-57; slice-2-report §4.4, limit 4), updated for ADR-046.
 *
 * <p>WAR-57 opened a window by locking {@code subscription.subscription} in SHARE mode so the sign-up's
 * insert waited after it had read the account but before a versioned update of it. A {@code set} of the
 * standing that committed in that window used to make the sign-up answer 409 {@code /problems/concurrent-change}.
 *
 * <p>Sign-up now locks the account row when it records the payment token (ADR-046), and {@code set} takes
 * the same lock. Calling {@code set} while this test still holds the SHARE lock would deadlock (the sign-up
 * waits for the table, {@code set} waits for the account). So {@code set} is started only as a second
 * waiter; the table lock is then released. The sign-up commits first; {@code set} applies after. Both
 * succeed: the sign-up is 201, the standing is OVERDUE, and the subscription is kept.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpikeCollectionsS4SetDuringSignUpIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    AccountStanding standing;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    /** One household for each test: both requests of a race come from it. */
    final TestTokens.Household household = TestTokens.household();

    ResponseEntity<String> signUp(UUID account, String key) {
        return household.client(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }))
                .post().uri("/v1/subscriptions").header("Idempotency-Key", key)
                .body(Map.of("accountId", account, "planId", "BASIC", "paymentToken", "tok_s4_" + account))
                .retrieve().toEntity(String.class);
    }

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    @Test
    void aSetThatArrivesDuringASignUpWaitsThenBothSucceed() throws Exception {
        UUID account = testAccounts.openFor(household, Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();
        CompletableFuture<ResponseEntity<String>> signUp;
        CompletableFuture<Void> set;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("LOCK TABLE subscription.subscription IN SHARE MODE");
            signUp = CompletableFuture.supplyAsync(() -> signUp(account, key));
            await().atMost(Duration.ofSeconds(20)).until(() -> sessionsWaitingOnALock() >= 1);
            System.out.println("SPIKE-C-S4 sign-up waiting: " + jdbc.queryForList("SELECT wait_event, query"
                    + " FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"));

            // Started while the sign-up holds the account row; it waits rather than racing the version.
            set = CompletableFuture.runAsync(() -> standing.set(account, Standing.OVERDUE, "spike S-4"));
            await().atMost(Duration.ofSeconds(20)).until(() -> sessionsWaitingOnALock() >= 2);
            System.out.println("SPIKE-C-S4 set also waiting: " + jdbc.queryForList("SELECT wait_event, query"
                    + " FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"));
            holder.rollback();
        }
        ResponseEntity<String> answer = signUp.get(20, TimeUnit.SECONDS);
        set.get(20, TimeUnit.SECONDS);

        System.out.println("SPIKE-C-S4 answer: " + answer.getStatusCode().value() + " " + answer.getBody());
        assertThat(answer.getStatusCode().value()).as("the sign-up is not failed by the waiting set").isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?",
                Long.class, account)).isEqualTo(1);
        assertThat(accounts.get(account).orElseThrow().standing()).isEqualTo(Standing.OVERDUE);
    }
}
