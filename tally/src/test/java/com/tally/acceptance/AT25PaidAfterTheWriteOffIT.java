package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.account.Standing;
import com.tally.collections.CaseStatus;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AT-25. Paid after the write-off (Slice 3 brief, decision 4). Day 35: "pay now" succeeds. The standing is GOOD. The
 * subscription stays ENDED. The account signs up again, and Access says yes.
 *
 * <p>The household signs up on the 1st of a month whose next month has 31 days, so day 30 comes before the next
 * renewal date, as in AT-22.
 */
class AT25PaidAfterTheWriteOffIT extends AbstractCollectionsAcceptanceTest {

    @Test
    void aWrittenOffHouseholdThatPaysIsGoodAgainAndSignsUpAgain() {
        // The renewal month (the one after the sign-up month) must have 31 days.
        while (LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusMonths(2).lengthOfMonth() != 31) {
            clock.set(clock.instant().plus(Duration.ofDays(28)));
        }
        UUID account = signUpAtTheStartOfAMonth();
        Instant firstDecline = renewalDeclined(account);
        untilDay(firstDecline, 35, account);
        assertThat(theCase(account).status()).isEqualTo(CaseStatus.WRITTEN_OFF);
        assertThat(standing(account)).isEqualTo(Standing.BLOCKED);
        assertThat(subscriptionStatuses(account)).containsExactly("ENDED");

        FakeProvider.mode("NORMAL");
        assertThat(payNow(account).getStatusCode().value()).isEqualTo(202);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));

        assertThat(theCase(account).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(standing(account)).isEqualTo(Standing.GOOD);
        assertThat(subscriptionStatuses(account)).as("the subscription stays ended").containsExactly("ENDED");

        assertThat(signUp(account)).as("the account signs up again").isEqualTo(201);
        assertThat(awaitAnswer(account, Duration.ofSeconds(15), a -> Boolean.TRUE.equals(a.get("canWatch"))))
                .containsEntry("reason", "ACTIVE_SOURCE");
        assertThat(subscriptionStatuses(account)).containsExactlyInAnyOrder("ENDED", "ACTIVE");
    }

    List<String> subscriptionStatuses(UUID account) {
        return jdbc.queryForList("SELECT status FROM subscription.subscription WHERE account_id = ?", String.class,
                account);
    }
}
