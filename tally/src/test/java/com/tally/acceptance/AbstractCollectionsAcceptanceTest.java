package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.account.Standing;
import com.tally.collections.CaseView;
import com.tally.collections.Collections;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoicingApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import com.tally.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The journey of the Slice 3 acceptance tests (AT-23, AT-25): a household signs up on the 1st of a month at 09:00 UTC,
 * its first invoice is paid, and its renewal a month later, at midnight, is declined. Billing runs in the test's
 * context with its pollers on; Access is a container reading the context's topic, as in AT-20. The clock moves by
 * days counted from the case's first decline, and the test waits until the work of the day is done (Quiesce).
 *
 * <p>The fake provider's mode is the same for every account, so each test runs its own household from sign-up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"tally.platform.polling-enabled=true", "tally.platform.poll-interval=100ms"})
abstract class AbstractCollectionsAcceptanceTest extends AbstractProviderIntegrationTest {

    @LocalServerPort
    int port;

    @Value("${tally.accessfeed.log.topic}")
    String topic;

    @Autowired
    AccountApi accounts;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    Collections collections;

    @Autowired
    JdbcTemplate jdbc;

    AccessService access;
    Instance instance;

    @BeforeEach
    void startAccess() {
        access = AccessService.on(topic);
        instance = access.start(getClass().getSimpleName().substring(0, 4).toLowerCase());
    }

    @AfterEach
    void stopAccess() {
        FakeProvider.mode("NORMAL");
        if (access != null) {
            access.close();
        }
    }

    /** Each account's household: the user who opened it, and that user's token. */
    final Map<UUID, TestTokens.Household> households = new ConcurrentHashMap<>();

    RestClient tally(TestTokens.Household household) {
        return household.client(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }));
    }

    /** The client of the household that opened {@code account}. */
    RestClient tally(UUID account) {
        return tally(households.get(account));
    }

    /** Signs up over HTTP, on the 1st of the next month at 09:00 UTC; returns once the first invoice is paid. */
    UUID signUpAtTheStartOfAMonth() {
        LocalDate first = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1);
        clock.set(first.atTime(9, 0).toInstant(ZoneOffset.UTC));
        UUID account = openAccount();
        assertThat(signUp(account)).isEqualTo(201);
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID));
        return account;
    }

    /** A new household opens an account over HTTP, in USD and UTC. */
    UUID openAccount() {
        TestTokens.Household household = TestTokens.household();
        UUID account = UUID.fromString((String) tally(household).post().uri("/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("currency", "USD", "timeZone", "UTC"))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("id"));
        households.put(account, household);
        return account;
    }

    int signUp(UUID account) {
        return tally(account).post().uri("/v1/subscriptions").header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", FakeProvider.token()))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    /** The card stops working; the clock moves a day at a time to the renewal, whose charge is declined. */
    Instant renewalDeclined(UUID account) {
        FakeProvider.mode("DECLINE_HARD(100)");
        Instant renewal = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        while (clock.instant().isBefore(renewal)) {
            Instant next = clock.instant().plus(Duration.ofDays(1));
            clock.set(next.isAfter(renewal) ? renewal : next);
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        }
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        return collections.caseFor(account).orElseThrow().openedAt();
    }

    /** Moves the clock day by day to {@code day} days (and two hours) after the first decline. */
    void untilDay(Instant firstDecline, int day, UUID account) {
        Instant target = firstDecline.plus(Duration.ofDays(day)).plus(Duration.ofHours(2));
        while (clock.instant().isBefore(target)) {
            Instant next = clock.instant().plus(Duration.ofDays(1));
            clock.set(next.isAfter(target) ? target : next);
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        }
    }

    ResponseEntity<Map<String, Object>> payNow(UUID account) {
        return tally(account).post().uri("/v1/accounts/{id}/collections/payment-requests", account)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .retrieve().toEntity(new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    CaseView theCase(UUID account) {
        return collections.caseFor(account).orElseThrow();
    }

    Standing standing(UUID account) {
        return accounts.get(account).orElseThrow().standing();
    }

    long standingChanges(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE '%StandingChanged'"
                + " AND serialized_event LIKE ?", Long.class, "%" + account + "%");
    }

    Map<String, Object> awaitAnswer(UUID account, Duration atMost, Predicate<Map<String, Object>> until) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(100))
                .until(() -> instance.status(account) == 200 && until.test(instance.answer(account)));
        return instance.answer(account);
    }

    static long sequence(Map<String, Object> answer) {
        return ((Number) answer.get("sequence")).longValue();
    }
}
