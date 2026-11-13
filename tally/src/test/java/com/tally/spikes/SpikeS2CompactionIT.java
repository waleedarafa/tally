package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.support.EventLog;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.utils.AppInfoParser;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * Slice 2 spikes S-1 and S-2 (brief "Spikes, before PR-2"). Findings: docs/briefs/slice-2-spikes.md. Prints
 * evidence with SPIKE-S1 and SPIKE-S2 and asserts what was observed.
 *
 * <p>S-2: ADR-012 assumes a rebuild reads one message per account. Kafka's documentation promises less: a reader
 * from the start sees "at least the final state", and compaction "does not guarantee there is only one record with
 * the same key". This spike shows both facts on a real broker, and which topic settings make compaction run.
 */
class SpikeS2CompactionIT {

    /**
     * Topic settings that let compaction run within seconds. The cleaner never touches the active segment, so the
     * segment must roll: {@code segment.ms} rolls it when a message arrives after 100 ms. Any dirty ratio, and no
     * minimum age, makes the closed segment eligible at once.
     */
    static final Map<String, String> COMPACT_NOW = Map.of(
            "cleanup.policy", "compact",
            "segment.ms", "100",
            "min.cleanable.dirty.ratio", "0.01",
            "min.compaction.lag.ms", "0",
            "delete.retention.ms", "100");

    @Test
    void s1TheClientAndTheBrokerAreTheVersionsSpringBootManages() throws Exception {
        ExecResult broker = EventLog.container().execInContainer("/opt/kafka/bin/kafka-topics.sh", "--version");
        System.out.println("SPIKE-S1 client kafka-clients " + AppInfoParser.getVersion() + ", broker image "
                + EventLog.IMAGE + ", broker reports " + broker.getStdout().strip());
        assertThat(AppInfoParser.getVersion()).isEqualTo("4.2.1");
        assertThat(broker.getStdout()).startsWith("4.2.1");
    }

    @Test
    void s2BeforeCompactionAReaderSeesEveryMessageOfAKeyAfterItOnlyTheLast() {
        String topic = EventLog.newTopic("spike-s2", COMPACT_NOW);
        try (KafkaProducer<String, String> producer = EventLog.producer()) {
            for (int sequence = 1; sequence <= 5; sequence++) {
                EventLog.send(producer, topic, "account-a", "a" + sequence);
            }
            for (int sequence = 1; sequence <= 3; sequence++) {
                EventLog.send(producer, topic, "account-b", "b" + sequence);
            }

            Map<String, List<String>> before = byKey(EventLog.readAll(topic));
            System.out.println("SPIKE-S2 before compaction, from the start: " + before);
            assertThat(before.get("account-a")).containsExactly("a1", "a2", "a3", "a4", "a5");
            assertThat(before.get("account-b")).containsExactly("b1", "b2", "b3");

            // Rolls the segment that holds the eight messages: the next message after segment.ms opens a new one.
            sleep(Duration.ofMillis(300));
            EventLog.send(producer, topic, "roll", "roll");
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                    .until(() -> byKey(EventLog.readAll(topic)).get("account-a").size() == 1);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            List<ConsumerRecord<String, String>> after = EventLog.readAll(topic);
            System.out.println("SPIKE-S2 after compaction (" + took.toMillis() + " ms after the roll), from the start: "
                    + after.stream().map(r -> r.offset() + ":" + r.key() + "=" + r.value()).toList());
            assertThat(byKey(after).get("account-a")).containsExactly("a5");
            assertThat(byKey(after).get("account-b")).containsExactly("b3");
            // Compaction removes messages and never reorders the rest: offsets keep their order.
            assertThat(after).extracting(ConsumerRecord::offset).isSorted();
            assertThat(after).extracting(ConsumerRecord::offset).containsExactly(4L, 7L, 8L);

            // A change after compaction lands in the active segment, which the cleaner never touches: a reader from
            // the start now sees two messages for account-a. So a rebuild must apply by sequence, never "last wins
            // because there is only one".
            EventLog.send(producer, topic, "account-a", "a6");
            Map<String, List<String>> later = byKey(EventLog.readAll(topic));
            System.out.println("SPIKE-S2 one more change after compaction, from the start: " + later);
            assertThat(later.get("account-a")).containsExactly("a5", "a6");
        }
    }

    @Test
    void s2WithTheDefaultSettingsNothingIsCompactedWhileTheTestRuns() {
        String topic = EventLog.newTopic("spike-s2-default", Map.of("cleanup.policy", "compact"));
        try (KafkaProducer<String, String> producer = EventLog.producer()) {
            for (int sequence = 1; sequence <= 5; sequence++) {
                EventLog.send(producer, topic, "account-a", "a" + sequence);
            }
            sleep(Duration.ofMillis(300));
            EventLog.send(producer, topic, "roll", "roll");
            sleep(Duration.ofSeconds(5));
            Map<String, List<String>> seen = byKey(EventLog.readAll(topic));
            System.out.println("SPIKE-S2 default settings, 5 s after the last send: " + seen);
            assertThat(seen.get("account-a")).containsExactly("a1", "a2", "a3", "a4", "a5");
        }
    }

    static Map<String, List<String>> byKey(List<ConsumerRecord<String, String>> records) {
        return records.stream().collect(Collectors.groupingBy(ConsumerRecord::key, LinkedHashMap::new,
                Collectors.mapping(ConsumerRecord::value, Collectors.toList())));
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
