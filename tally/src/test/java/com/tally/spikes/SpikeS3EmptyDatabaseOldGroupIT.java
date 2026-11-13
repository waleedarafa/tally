package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.support.EventLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * Slice 2 spike S-3 (brief "Spikes, before PR-2"): Access restarts with an empty database and the consumer group it
 * had before. Does it read from the start? What must an operator reset? Findings: docs/briefs/slice-2-spikes.md.
 * Prints evidence with SPIKE-S3 and asserts what was observed.
 */
class SpikeS3EmptyDatabaseOldGroupIT {

    static final String GROUPS = "/opt/kafka/bin/kafka-consumer-groups.sh";

    /** The broker's own listener inside its container. */
    static final String INSIDE = "localhost:9093";

    @Test
    void anOldGroupResumesFromItsCommittedPositionUntilAnOperatorResetsItToTheEarliest() throws Exception {
        String topic = EventLog.newTopic("spike-s3", Map.of("cleanup.policy", "compact"));
        String group = "access-" + UUID.randomUUID();
        try (KafkaProducer<String, String> producer = EventLog.producer()) {
            for (int i = 1; i <= 3; i++) {
                EventLog.send(producer, topic, "account-" + i, "facts-" + i);
            }

            // Access reads the log and confirms its position after each commit to its database.
            try (KafkaConsumer<String, String> access = EventLog.consumer(group)) {
                access.subscribe(List.of(topic));
                assertThat(pollFor(access, 3, Duration.ofSeconds(30))).hasSize(3);
                access.commitSync();
            }

            // Its database is emptied. It starts again with the same group: auto.offset.reset=earliest does not
            // apply, because the group has a committed position.
            try (KafkaConsumer<String, String> restarted = EventLog.consumer(group)) {
                restarted.subscribe(List.of(topic));
                List<ConsumerRecord<String, String>> seen = pollFor(restarted, 1, Duration.ofSeconds(10));
                System.out.println("SPIKE-S3 same group, empty database: " + seen.size() + " messages in 10 s");
                assertThat(seen).isEmpty();

                // While a member is running, the reset is refused.
                ExecResult refused = resetToEarliest(group, topic);
                System.out.println("SPIKE-S3 reset with a running member: exit " + refused.getExitCode() + ", "
                        + (refused.getStdout() + refused.getStderr()).strip());
                // The tool refuses, and still exits 0: a runbook must read its output, not its exit code.
                assertThat(refused.getStdout() + refused.getStderr()).contains("can only be reset if the group")
                        .contains("is inactive");
                assertThat(refused.getExitCode()).isZero();
            }

            // Every member stopped: the reset works.
            ExecResult reset = resetToEarliest(group, topic);
            System.out.println("SPIKE-S3 reset with no member: exit " + reset.getExitCode() + "\n"
                    + (reset.getStdout() + reset.getStderr()).strip());
            assertThat(reset.getExitCode()).isZero();
            assertThat(reset.getStdout() + reset.getStderr()).doesNotContain("Error");

            try (KafkaConsumer<String, String> rebuilt = EventLog.consumer(group)) {
                rebuilt.subscribe(List.of(topic));
                List<ConsumerRecord<String, String>> seen = pollFor(rebuilt, 3, Duration.ofSeconds(30));
                System.out.println("SPIKE-S3 after the reset: " + seen.size() + " messages, from offset "
                        + seen.getFirst().offset());
                assertThat(seen).extracting(ConsumerRecord::value).containsExactly("facts-1", "facts-2", "facts-3");
            }
        }
    }

    @Test
    void aNewGroupReadsFromTheStartWithNoReset() {
        String topic = EventLog.newTopic("spike-s3-new", Map.of("cleanup.policy", "compact"));
        try (KafkaProducer<String, String> producer = EventLog.producer()) {
            for (int i = 1; i <= 3; i++) {
                EventLog.send(producer, topic, "account-" + i, "facts-" + i);
            }
            try (KafkaConsumer<String, String> fresh = EventLog.consumer("access-" + UUID.randomUUID())) {
                fresh.subscribe(List.of(topic));
                List<ConsumerRecord<String, String>> seen = pollFor(fresh, 3, Duration.ofSeconds(30));
                System.out.println("SPIKE-S3 new group: " + seen.size() + " messages, from offset "
                        + seen.getFirst().offset());
                assertThat(seen).hasSize(3);
            }
        }
    }

    static ExecResult resetToEarliest(String group, String topic) throws Exception {
        return EventLog.container().execInContainer(GROUPS, "--bootstrap-server", INSIDE, "--group", group,
                "--topic", topic, "--reset-offsets", "--to-earliest", "--execute");
    }

    /** Polls until {@code wanted} messages arrived or the time is up; returns what arrived. */
    static List<ConsumerRecord<String, String>> pollFor(KafkaConsumer<String, String> consumer, int wanted,
            Duration limit) {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        long deadline = System.nanoTime() + limit.toNanos();
        while (seen.size() < wanted && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
        }
        return seen;
    }
}
