package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-037: money queues are flagged in their registration, and their health metrics carry
 * {@code money="true"} for the alert rules to select on (slice 2, step 0). The list is pinned, so a
 * new queue's money decision shows up in review.
 */
class MoneyQueuesIT extends AbstractIntegrationTest {

    @Autowired
    JdbcDurableQueues queues;

    @Autowired
    MeterRegistry meters;

    @Test
    void theRenewalsPaymentCollectionAndCollectionsRetryAreTheMoneyQueues() {
        // collections-retry since Slice 1R PR-8: a retry collects money.
        assertThat(queues.queues()).filteredOn(q -> q.definition().money()).extracting(JdbcDurableQueue::name)
                .containsExactlyInAnyOrder("renewals", "payment-collection", "collections-retry");

        for (JdbcDurableQueue queue : queues.queues()) {
            String money = String.valueOf(queue.definition().money());
            for (String metric : new String[] {"tally.queue.failed", "tally.queue.waiting",
                    "tally.queue.oldest_due_age_seconds", "tally.queue.overdue"}) {
                assertThat(meters.find(metric).tag("queue", queue.name()).tag("money", money).gauge())
                        .as("%s{queue=%s, money=%s}", metric, queue.name(), money).isNotNull();
            }
        }
    }
}
