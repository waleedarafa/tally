package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.account.Standing;
import com.tally.collections.CaseStatus;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
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
 * </ol>
 * The brief's last clause of (c), "the renewal that waited runs within one day", needs the renewal to wait while the
 * account is BLOCKED, which Slice 3 PR-6 builds; PR-6 adds it to this test.
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
}
