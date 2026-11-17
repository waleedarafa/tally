package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.Standing;
import com.tally.collections.CaseStatus;
import com.tally.invoicing.InvoiceStatus;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * AT-23. The household pays (Slice 3 brief; design.md §5, proposed in ADR-037). Three cases, each its own household,
 * each after a declined renewal:
 * <ol type="a">
 *   <li>Day 3: the retry succeeds; the case closes; the standing was never changed.</li>
 *   <li>Day 8, OVERDUE: "pay now" succeeds; the standing is GOOD.</li>
 *   <li>Day 12, BLOCKED: "pay now" succeeds; the standing is GOOD; Access says yes again within 5 s.</li>
 *   <li>The renewal that waited (Slice 3 PR-6; decision 3): the 1 February renewal is declined, so the next renewal
 *       date, 1 March (day 28), falls while the account is BLOCKED, and the renewal waits. Day 29: "pay now"
 *       succeeds; the standing is GOOD; the renewal that waited runs within one day, for the period from 1 March.</li>
 * </ol>
 * On day 12 no renewal date has come yet, so (c) cannot hold the brief's last clause, "the renewal that waited runs
 * within one day"; case (d) holds it (the user's choice, 17 November 2026).
 */
class AT23HouseholdPaysIT extends AbstractCollectionsAcceptanceTest {

    @Test
    void a_theDayThreeRetrySucceedsAndTheStandingNeverChanged() {
        UUID account = signUpAtTheStartOfAMonth();
        Instant firstDecline = renewalDeclined(account);

        FakeProvider.mode("NORMAL");
        untilDay(firstDecline, 3, account);

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(theCase(account).retries()).isEqualTo(1);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        assertThat(standingChanges(account)).as("the standing was never changed").isZero();
    }

    @Test
    void b_payNowWhileOverdueMakesTheStandingGood() {
        UUID account = signUpAtTheStartOfAMonth();
        Instant firstDecline = renewalDeclined(account);
        untilDay(firstDecline, 8, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.OVERDUE);
        assertThat(standing(account)).isEqualTo(Standing.OVERDUE);

        FakeProvider.mode("NORMAL");
        ResponseEntity<Map<String, Object>> asked = payNow(account);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        assertThat(asked.getStatusCode().value()).isEqualTo(202);
        assertThat(asked.getBody()).containsEntry("retry", 3);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
    }

    @Test
    void c_payNowWhileBlockedMakesTheStandingGoodAndAccessSaysYesWithinFiveSeconds() {
        UUID account = signUpAtTheStartOfAMonth();
        Instant firstDecline = renewalDeclined(account);
        untilDay(firstDecline, 12, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.BLOCKED);
        Map<String, Object> blocked = awaitAnswer(account, Duration.ofSeconds(15),
                a -> "ACCOUNT_BLOCKED".equals(a.get("reason")));

        FakeProvider.mode("NORMAL");
        long asked = System.nanoTime();
        assertThat(payNow(account).getStatusCode().value()).isEqualTo(202);
        Map<String, Object> restored = awaitAnswer(account, Duration.ofSeconds(5),
                a -> Boolean.TRUE.equals(a.get("canWatch")));
        Duration within = Duration.ofNanos(System.nanoTime() - asked);

        System.out.printf("AT-23 (c): Access says yes %d ms after pay now; sequences %d (blocked), %d (restored)%n",
                within.toMillis(), sequence(blocked), sequence(restored));
        assertThat(restored).containsEntry("reason", "ACTIVE_SOURCE");
        assertThat(sequence(restored)).isGreaterThan(sequence(blocked));
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
    }

    @Test
    void d_theRenewalThatWaitedWhileBlockedRunsWithinOneDayOfGood() {
        // The renewal of 1 February is declined: sign up on 1 January.
        while (LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusMonths(1).getMonth() != Month.JANUARY) {
            clock.set(clock.instant().plus(Duration.ofDays(28)));
        }
        UUID account = signUpAtTheStartOfAMonth();
        Instant firstDecline = renewalDeclined(account);
        LocalDate march = LocalDate.ofInstant(firstDecline, ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1);
        untilDay(firstDecline, 29, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(clock.instant()).isAfter(march.atStartOfDay().toInstant(ZoneOffset.UTC));
        assertThat(invoicing.invoicesFor(account)).as("the 1 March renewal waits: no invoice while blocked").hasSize(2);

        FakeProvider.mode("NORMAL");
        assertThat(payNow(account).getStatusCode().value()).isEqualTo(202);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        Instant good = clock.instant();
        while (invoicing.invoicesFor(account).size() < 3 && clock.instant().isBefore(good.plus(Duration.ofDays(1)))) {
            clock.advance(Duration.ofMinutes(30));
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
        }
        Duration waited = Duration.between(good, clock.instant());

        System.out.printf("AT-23 (d): the renewal that waited ran %d min after the standing was GOOD%n",
                waited.toMinutes());
        assertThat(waited).as("within one day").isLessThanOrEqualTo(Duration.ofDays(1));
        assertThat(invoicing.invoicesFor(account)).hasSize(3);
        assertThat(invoicing.invoicesFor(account).getLast().periodStart()).as("the period keeps its dates")
                .isEqualTo(march);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoicing.invoicesFor(account).getLast().status() == InvoiceStatus.PAID);
        assertThat(awaitAnswer(account, Duration.ofSeconds(15), a -> Boolean.TRUE.equals(a.get("canWatch"))))
                .containsEntry("reason", "ACTIVE_SOURCE");
    }
}
