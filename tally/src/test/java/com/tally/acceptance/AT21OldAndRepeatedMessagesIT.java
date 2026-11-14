package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.EventLog;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * AT-21. Old and repeated messages (ADR-006, ADR-016). Access gets sequence 6, then 5, then 6 again. Pass when the
 * answer stays on the first 6.
 *
 * <p>The messages are sent as billing's relay sends them (one transaction each, ADR-033), to Access as a container.
 * Billing cannot be made to send an older sequence, which is why this test writes to the log itself. A last message
 * for another account, on the same partition, shows that Access has read the 5 and the second 6.
 */
class AT21OldAndRepeatedMessagesIT {

    static String topic;
    static AccessService access;
    static Instance instance;
    static KafkaProducer<String, String> producer;

    @BeforeAll
    static void start() {
        topic = EventLog.newTopic("at21", Map.of("cleanup.policy", "compact"));
        access = AccessService.on(topic);
        instance = access.start("at21");
        producer = EventLog.committedProducer();
    }

    @AfterAll
    static void stop() {
        if (producer != null) {
            producer.close();
        }
        if (access != null) {
            access.close();
        }
    }

    void send(UUID account, long sequence, String standing) {
        EventLog.sendCommitted(producer, topic, null, account.toString(), AccessFacts.json(account, sequence,
                standing, "ACTIVE"));
    }

    @Test
    void sixThenFiveThenSixAgainStaysOnTheFirstSix() {
        UUID account = UUID.randomUUID();
        send(account, 6, "GOOD");
        await().atMost(Duration.ofSeconds(30)).until(() -> instance.status(account) == 200
                && Long.valueOf(6).equals(number(instance.answer(account).get("sequence"))));

        send(account, 5, "BLOCKED");
        send(account, 6, "BLOCKED");
        UUID last = UUID.randomUUID();
        send(last, 1, "GOOD");
        await().atMost(Duration.ofSeconds(30)).until(() -> "ACTIVE_SOURCE".equals(instance.answer(last).get("reason")));

        Map<String, Object> answer = instance.answer(account);
        assertThat(number(answer.get("sequence"))).isEqualTo(6);
        assertThat(answer).containsEntry("canWatch", true).containsEntry("reason", "ACTIVE_SOURCE");
    }

    static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
