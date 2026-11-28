package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.account.Standing;
import com.tally.collections.CaseStatus;
import com.tally.collections.CaseView;
import com.tally.collections.Collections;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import com.tally.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * AT-22. The household never pays (Slice 3 brief; design.md §5, proposed in ADR-037). A renewal is declined. Day 3
 * and day 7: declined again; standing OVERDUE; Access says yes. Day 10: standing BLOCKED; Access says no,
 * {@code ACCOUNT_BLOCKED}, with a higher sequence. Day 30: the case is WRITTEN_OFF; the subscription is ENDED; no later
 * renewal makes an invoice. The whole test runs in under 1 minute (D10).
 *
 * <p>The account signs up on 1 February 2027, so its renewal on 1 March is declined, and day 30 (31 March) comes
 * before the next renewal date (1 April). The clock moves one day at a time, and the test waits until the work of
 * the day is done (Quiesce) before it looks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"tally.platform.polling-enabled=true", "tally.platform.poll-interval=100ms"})
class AT22HouseholdNeverPaysIT extends AbstractProviderIntegrationTest {

    static final Instant SIGN_UP = Instant.parse("2027-02-01T09:00:00Z");

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
        instance = access.start("at22");
    }

    @AfterEach
    void stopAccess() {
        FakeProvider.mode("NORMAL");
        if (access != null) {
            access.close();
        }
    }

    UUID signUp() {
        RestClient tally = TestTokens.household().client("http://localhost:" + port);
        UUID account = UUID.fromString((String) tally.post().uri("/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("currency", "USD", "timeZone", "UTC", "country", "US"))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("id"));
        int status = tally.post().uri("/v1/subscriptions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", FakeProvider.token()))
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(201);
        return account;
    }

    Map<String, Object> awaitAnswer(UUID account, Predicate<Map<String, Object>> until) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> instance.status(account) == 200 && until.test(instance.answer(account)));
        return instance.answer(account);
    }

    static long sequence(Map<String, Object> answer) {
        return ((Number) answer.get("sequence")).longValue();
    }

    Standing standing(UUID account) {
        return accounts.get(account).orElseThrow().standing();
    }

    String subscriptionStatus(UUID account) {
        return jdbc.queryForObject("SELECT status FROM subscription.subscription WHERE account_id = ?", String.class,
                account);
    }

    @Test
    void theHouseholdThatNeverPaysIsOverdueThenBlockedThenWrittenOff() {
        long started = System.nanoTime();
        clock.set(SIGN_UP);
        UUID account = signUp();
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID));
        long signedUp = sequence(awaitAnswer(account, a -> Boolean.TRUE.equals(a.get("canWatch"))));

        // The card stops working before the renewal on 1 March.
        FakeProvider.mode("DECLINE_HARD(100)");
        Instant renewal = Instant.parse("2027-03-01T00:00:00Z");
        while (clock.instant().isBefore(renewal)) {
            clock.set(clock.instant().plus(Duration.ofDays(1)).isAfter(renewal) ? renewal
                    : clock.instant().plus(Duration.ofDays(1)));
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        }
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        CaseView opened = collections.caseFor(account).orElseThrow();
        InvoiceView declined = invoicing.invoicesFor(account).getLast();
        assertThat(opened.invoiceIds()).as("the renewal's invoice").containsExactly(declined.id());
        Instant firstDecline = opened.openedAt();

        Map<String, Object> overdueAnswer = null;
        Map<String, Object> blockedAnswer = null;
        for (int day = 1; day <= 33; day++) {
            clock.set(firstDecline.plus(Duration.ofDays(day)).plus(Duration.ofHours(2)));
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
            CaseView now = collections.caseFor(account).orElseThrow();
            switch (day) {
                case 3 -> {
                    assertThat(now.retries()).as("day 3: retry 1").isEqualTo(1);
                    assertThat(now.status()).isEqualTo(CaseStatus.OPEN);
                    assertThat(standing(account)).isEqualTo(Standing.GOOD);
                }
                case 7 -> {
                    assertThat(now.retries()).as("day 7: retry 2").isEqualTo(2);
                    assertThat(now.status()).as("one hour after retry 2").isEqualTo(CaseStatus.OVERDUE);
                    assertThat(standing(account)).isEqualTo(Standing.OVERDUE);
                    // Access has the OVERDUE facts (a higher sequence) and still says yes.
                    overdueAnswer = awaitAnswer(account, a -> sequence(a) > signedUp);
                }
                case 10 -> {
                    assertThat(now.status()).isEqualTo(CaseStatus.BLOCKED);
                    assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
                    blockedAnswer = awaitAnswer(account, a -> "ACCOUNT_BLOCKED".equals(a.get("reason")));
                }
                case 30 -> {
                    assertThat(now.status()).isEqualTo(CaseStatus.WRITTEN_OFF);
                    assertThat(subscriptionStatus(account)).isEqualTo("ENDED");
                    assertThat(standing(account)).as("decision 4: it stays BLOCKED").isEqualTo(Standing.BLOCKED);
                }
                default -> {
                }
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        List<String> attempts = jdbc.queryForList("SELECT number || '=' || state FROM payment.payment_attempt"
                + " WHERE reference = ? ORDER BY number", String.class, declined.paymentReference());
        System.out.printf("AT-22: attempts %s; Access sequences %d (overdue), %d (blocked); %d invoices; %d ms%n",
                attempts, sequence(overdueAnswer), sequence(blockedAnswer), invoicing.invoicesFor(account).size(),
                elapsed.toMillis());
        assertThat(attempts).as("the renewal, retry 1, retry 2: all declined")
                .containsExactly("1=DECLINED", "2=DECLINED", "3=DECLINED");
        assertThat(overdueAnswer).containsEntry("canWatch", true);
        assertThat(blockedAnswer).containsEntry("canWatch", false);
        assertThat(sequence(blockedAnswer)).isGreaterThan(sequence(overdueAnswer));
        assertThat(invoicing.invoicesFor(account)).as("1 April passed: no later renewal makes an invoice").hasSize(2);
        assertThat(elapsed).as("D10: 30 days in under a minute").isLessThan(Duration.ofMinutes(1));
    }
}
