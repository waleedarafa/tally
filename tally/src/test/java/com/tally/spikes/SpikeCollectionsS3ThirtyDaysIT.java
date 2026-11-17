package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.collections.CaseStatus;
import com.tally.collections.Collections;
import com.tally.invoicing.InvoiceView;
import com.tally.payment.AttemptState;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Slice 3 spike S-3 (D10: 30 days of renewal and failed payment in a test, under a minute): can one acceptance test
 * move the clock through 30 days, with four Collections timers and a renewal date, in under 1 minute, on the harness
 * that exists?
 *
 * <p>The household's card is declined every time. The real pieces run: sign-up, the first charge declined, the case
 * opened, Collections' day-3 retry (declined), and Subscription's renewal at the end of the first period (its charge
 * declined). Days 7, 10 and 30 have no code yet, so three timers on the platform's test queue stand in for them, due
 * at the same times, claimed and handled by the same pollers. The clock moves one day at a time, as AT-22 will, and the
 * test waits until the work of the day is done (Quiesce) before each move.
 *
 * <p>Since Slice 3 PR-4 the real timers run beside the stand-ins, and the day-30 write-off ends the subscription
 * before its renewal date, so no renewal invoice follows. The spike now measures that; AT-22 is D10's real test. Since
 * PR-7 the case starts from a declined renewal: a declined first invoice ends the subscription and opens no case.
 */
@Import(SpikeCollectionsS3ThirtyDaysIT.Timers.class)
@TestPropertySource(properties = "tally.test.context=spike-collections-s3")
class SpikeCollectionsS3ThirtyDaysIT extends AbstractReconcilingTest {

    static final Set<String> HANDLED = ConcurrentHashMap.newKeySet();

    /** Stand-ins for the day-7, day-10 and day-30 timers. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Timers {

        @Bean
        DurableQueue collectionsTimersStandIn(DurableQueues queues) {
            QueueDefinition definition = QueueDefinition.of("spike-collections-timers", "platform_test.work_queue",
                    "platform_test.work_done_one_per_key");
            return queues.register(definition, item -> {
                HANDLED.add(item.key());
                return Outcome.done();
            });
        }
    }

    @Autowired
    Collections collections;

    @Autowired
    DurableQueue collectionsTimersStandIn;

    @AfterEach
    void providerNormal() {
        FakeProvider.mode("NORMAL");
    }

    @Test
    void thirtyDaysWithFourTimersAndARenewalRunInUnderOneMinute() {
        long started = System.nanoTime();
        InvoiceView first = declinedRenewal("DECLINE_HARD(100)");
        UUID account = first.accountId();
        awaitAttempt(first.paymentReference(), AttemptState.DECLINED, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account)
                .map(c -> c.status() == CaseStatus.OPEN).orElse(false));
        Instant declinedAt = clock.instant();
        for (int day : List.of(7, 10, 30)) {
            collectionsTimersStandIn.enqueue(account + ":day-" + day, declinedAt.plus(Duration.ofDays(day)),
                    account.toString());
        }
        long setUp = System.nanoTime();

        Map<Integer, BooleanSupplier> milestones = new LinkedHashMap<>();
        milestones.put(3, () -> collections.caseFor(account).map(c -> c.retries() == 1).orElse(false));
        milestones.put(7, () -> HANDLED.contains(account + ":day-7"));
        milestones.put(10, () -> HANDLED.contains(account + ":day-10"));
        milestones.put(30, () -> HANDLED.contains(account + ":day-30"));
        Map<Integer, Long> reachedAtMillis = new LinkedHashMap<>();
        int renewedOnDay = -1;
        for (int day = 1; day <= 33; day++) {
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(30));
            // The work of the day before is done: was the renewal among it?
            if (renewedOnDay < 0 && invoicing.invoicesFor(account).size() == 3) {
                renewedOnDay = day - 1;
            }
            if (day == 33) {
                break;
            }
            clock.advance(Duration.ofDays(1));
            BooleanSupplier milestone = milestones.get(day);
            if (milestone != null) {
                await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).until(milestone::getAsBoolean);
                reachedAtMillis.put(day, Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        String subscription = jdbc.queryForObject("SELECT status || ' ' || coalesce(end_reason, '-')"
                + " FROM subscription.subscription WHERE account_id = ?", String.class, account);

        System.out.printf("SPIKE-C-S3 set-up %d ms; milestones (day=ms since start) %s; declined renewal's period %s to %s;"
                + " subscription %s; %d invoices; total %d ms for 32 days%n",
                Duration.ofNanos(setUp - started).toMillis(), reachedAtMillis, first.periodStart(), first.periodEnd(),
                subscription, invoicing.invoicesFor(account).size(), elapsed.toMillis());
        // Since Slice 3 PR-4 the real day-30 timer writes the case off and ends the subscription; since PR-6 the next
        // renewal, due while the account is BLOCKED, waits, and then finds the subscription ended: no invoice.
        assertThat(renewedOnDay).as("no renewal invoice").isEqualTo(-1);
        assertThat(subscription).isEqualTo("ENDED WRITE_OFF");
        assertThat(reachedAtMillis).containsOnlyKeys(3, 7, 10, 30);
        assertThat(elapsed).as("D10: 30 days in under a minute").isLessThan(Duration.ofMinutes(1));
    }
}
