package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.support.ChaosDatabase;
import com.tally.support.FakeProvider;
import com.tally.support.TallyProcess;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AT-9. Two instances, kill one mid-run (D1). Pass when: exactly one invoice per account and billing
 * date. 5,000 accounts in CI ({@code -Dtally.at09.accounts} changes it; 1,000,000 belongs to staging).
 *
 * <p>Two real Tally processes, started from the packaged jar, on the real clock and a fresh database.
 * Every renewal is due. Once about a tenth of the invoices exist, instance A is killed with SIGKILL:
 * whatever it had claimed stays claimed until instance B's reaper returns it. The claim timeout and the
 * event resubmission age are shortened so the test finishes in minutes; everything else is production.
 */
class AT09KillOneInstanceIT {

    static final int ACCOUNTS = Integer.getInteger("tally.at09.accounts", 5_000);

    final List<TallyProcess> instances = new ArrayList<>();

    @AfterEach
    void stop() {
        instances.forEach(TallyProcess::close);
    }

    static long count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void killingOneInstanceMidRunLeavesExactlyOneInvoicePerAccountAndDate() {
        ChaosDatabase db = ChaosDatabase.create("at09");
        JdbcTemplate jdbc = db.jdbc();
        Map<String, String> settings = Map.of(
                "tally.payment.provider.base-url", FakeProvider.baseUrl(),
                "tally.payment.provider.rate-limit-per-second", "2000",
                "tally.platform.claim-timeout", "15s",
                "tally.platform.reaper-interval", "2s",
                "tally.events.resubmit-older-than", "20s",
                "tally.events.resubmit-interval", "5s");
        TallyProcess a = TallyProcess.start("at09-a", db.url(), settings);
        instances.add(a);
        TallyProcess b = TallyProcess.start("at09-b", db.url(), settings);
        instances.add(b);

        LocalDate billingDate = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        YearMonth nextMonth = YearMonth.from(billingDate).plusMonths(1);
        LocalDate nextDate = nextMonth.atDay(Math.min(billingDate.getDayOfMonth(), nextMonth.lengthOfMonth()));
        long started = System.nanoTime();
        db.seedDueRenewals(ACCOUNTS, billingDate, FakeProvider.token());

        // Mid-run: kill instance A once about a tenth of the invoices exist.
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(100))
                .until(() -> count(jdbc, "SELECT count(*) FROM invoicing.invoice") >= ACCOUNTS / 10);
        long claimedByA = count(jdbc, "SELECT count(*) FROM subscription.renewal"
                + " WHERE status = 'IN_PROCESSING' AND claimed_by LIKE 'at09-a%'");
        long collectionsClaimedByA = count(jdbc, "SELECT count(*) FROM payment.collection_queue"
                + " WHERE status = 'IN_PROCESSING' AND claimed_by LIKE 'at09-a%'");
        long invoicesAtKill = count(jdbc, "SELECT count(*) FROM invoicing.invoice");
        a.kill();

        // Instance B finishes alone: every renewal done, every invoice's collection requested.
        await().atMost(Duration.ofMinutes(10)).pollInterval(Duration.ofSeconds(1)).until(() ->
                count(jdbc, "SELECT count(*) FROM subscription.renewal WHERE queue_key LIKE ? AND status = 'DONE'",
                        "%:" + billingDate) == ACCOUNTS
                        && count(jdbc, "SELECT count(DISTINCT reference) FROM payment.payment_attempt") == ACCOUNTS
                        && count(jdbc, "SELECT count(*) FROM payment.payment_attempt WHERE state IN ('REQUESTED', 'SENT')") == 0);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Exactly one invoice per account and billing date, for every account.
        assertThat(count(jdbc, "SELECT count(*) FROM invoicing.invoice WHERE period_start = ?", billingDate))
                .isEqualTo(ACCOUNTS);
        assertThat(count(jdbc, "SELECT count(DISTINCT account_id) FROM invoicing.invoice WHERE period_start = ?",
                billingDate)).isEqualTo(ACCOUNTS);
        assertThat(count(jdbc, "SELECT count(*) FROM (SELECT account_id FROM invoicing.invoice"
                + " GROUP BY account_id, period_start HAVING count(*) > 1) d")).isZero();
        // Exactly one next billing date scheduled per account.
        assertThat(count(jdbc, "SELECT count(*) FROM subscription.renewal WHERE queue_key LIKE ?", "%:" + nextDate))
                .isEqualTo(ACCOUNTS);
        // One payment attempt per invoice, plus one new attempt per technical failure (ADR-013). An attempt left
        // SENT by the killed instance goes stale and is resolved by a repeat with its own key, never by a new
        // attempt (proposed ADR-026 b).
        // Nothing left claimed.
        long technicalFailures = count(jdbc,
                "SELECT count(*) FROM payment.payment_attempt WHERE state = 'TECHNICAL_FAILURE'");
        assertThat(count(jdbc, "SELECT count(*) FROM payment.payment_attempt"))
                .isEqualTo(ACCOUNTS + technicalFailures);
        assertThat(count(jdbc, "SELECT count(*) FROM payment.payment_attempt WHERE reference NOT IN"
                + " (SELECT payment_reference FROM invoicing.invoice)")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM subscription.renewal WHERE status = 'IN_PROCESSING'")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM payment.collection_queue WHERE status = 'IN_PROCESSING'")).isZero();
        assertThat(count(jdbc, "SELECT count(*) FROM (SELECT reference FROM payment.payment_attempt"
                + " WHERE state = 'SUCCEEDED' GROUP BY reference HAVING count(*) > 1) d")).isZero();

        long renewalsRedone = count(jdbc, "SELECT count(*) FROM subscription.renewal"
                + " WHERE queue_key LIKE ? AND attempts > 1", "%:" + billingDate);
        long unknown = count(jdbc, "SELECT count(*) FROM payment.payment_attempt WHERE state = 'UNKNOWN'");
        long succeeded = count(jdbc, "SELECT count(*) FROM payment.payment_attempt WHERE state = 'SUCCEEDED'");
        System.out.printf("AT-9: %d accounts, A killed at %d invoices holding %d renewal and %d collection claims;"
                        + " B redid %d renewals; %d attempts SUCCEEDED, %d TECHNICAL_FAILURE and retried, %d UNKNOWN"
                        + " (in flight on A, left to reconciliation); %d s%n",
                ACCOUNTS, invoicesAtKill, claimedByA, collectionsClaimedByA, renewalsRedone, succeeded,
                technicalFailures, unknown, elapsed.toSeconds());
    }
}
