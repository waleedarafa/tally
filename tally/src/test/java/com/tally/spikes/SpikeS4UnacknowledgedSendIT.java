package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.support.EventLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2 spike S-4, found while building PR-5: does a send the broker did not acknowledge in time still reach the
 * log later? If it does, two relays on two instances can leave an older sequence after a newer one, which ADR-033's
 * property ("a later send never carries an older sequence") assumes cannot happen. And does a transactional
 * producer, read with {@code read_committed}, hide such a send? Prints evidence with SPIKE-S4.
 */
class SpikeS4UnacknowledgedSendIT {

    @AfterEach
    void brokerBack() {
        try {
            EventLog.unpause();
        } catch (RuntimeException notPaused) {
            // It was not paused.
        }
    }

    static Map<String, Object> producerSettings(Map<String, Object> extra) {
        Map<String, Object> settings = new java.util.HashMap<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, EventLog.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000));
        settings.putAll(extra);
        return settings;
    }

    static List<String> read(String topic, String isolation) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, EventLog.bootstrapServers());
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p, new StringDeserializer(),
                new StringDeserializer())) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (consumer.position(partition) < end && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach((ConsumerRecord<String, String> r) -> values.add(r.value()));
            }
        }
        return values;
    }

    @Test
    void aSendThatTimedOutStillReachesTheLogLater() throws Exception {
        String topic = EventLog.newTopic("spike-s4-plain", Map.of("cleanup.policy", "compact"));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerSettings(Map.of()),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, "account", "warm-up")).get(10, TimeUnit.SECONDS);
            EventLog.pause();
            assertThatThrownBy(() -> producer.send(new ProducerRecord<>(topic, "account", "sequence-5"))
                    .get(5, TimeUnit.SECONDS)).as("the broker does not answer").isNotNull();
            EventLog.unpause();
            Thread.sleep(3000);
        }
        List<String> seen = read(topic, "read_uncommitted");
        System.out.println("SPIKE-S4 plain producer, after a send that timed out: " + seen);
        assertThat(seen).as("the unacknowledged send landed after all").contains("sequence-5");
    }

    /**
     * The gap itself, driven: an older sequence written after a newer one, as a send that one instance gave up on can
     * land after another instance's acknowledged send. Without transactions (the first design), a committed reader
     * sees the older one last. In a transaction the relay never commits (the decision on PR-5), it never sees it.
     */
    @Test
    void anOlderSequenceWrittenAfterANewerOneIsLastWithoutTransactionsAndUnseenWithThem() throws Exception {
        String plain = EventLog.newTopic("spike-s4-gap-plain", Map.of("cleanup.policy", "compact"));
        String transactional = EventLog.newTopic("spike-s4-gap-transactional", Map.of("cleanup.policy", "compact"));
        try (KafkaProducer<String, String> newer = new KafkaProducer<>(producerSettings(
                Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "spike-s4-newer-" + UUID.randomUUID())),
                new StringSerializer(), new StringSerializer());
             KafkaProducer<String, String> lateWithoutTransaction = new KafkaProducer<>(producerSettings(Map.of()),
                new StringSerializer(), new StringSerializer());
             KafkaProducer<String, String> lateInTransaction = new KafkaProducer<>(producerSettings(Map.of(
                ProducerConfig.TRANSACTIONAL_ID_CONFIG, "spike-s4-late-" + UUID.randomUUID(),
                ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 3000)), new StringSerializer(), new StringSerializer())) {
            newer.initTransactions();
            lateInTransaction.initTransactions();
            for (String topic : List.of(plain, transactional)) {
                newer.beginTransaction();
                newer.send(new ProducerRecord<>(topic, "account", "sequence-6")).get(10, TimeUnit.SECONDS);
                newer.commitTransaction();
            }
            // The late write of sequence 5, after sequence 6. The first design: a plain send. The relay now: inside a
            // transaction that is never committed, because the relay was not told the send succeeded.
            lateWithoutTransaction.send(new ProducerRecord<>(plain, "account", "sequence-5")).get(10, TimeUnit.SECONDS);
            lateInTransaction.beginTransaction();
            lateInTransaction.send(new ProducerRecord<>(transactional, "account", "sequence-5"))
                    .get(10, TimeUnit.SECONDS);
            lateInTransaction.abortTransaction();
        }
        List<String> withoutTransactions = read(plain, "read_committed");
        List<String> withTransactions = read(transactional, "read_committed");
        System.out.println("SPIKE-S4 gap, committed reader: without transactions " + withoutTransactions
                + "; with them " + withTransactions + " (written: " + read(transactional, "read_uncommitted") + ")");
        assertThat(withoutTransactions).as("the first design: the older sequence is last").containsExactly(
                "sequence-6", "sequence-5");
        assertThat(withTransactions).as("the relay now: the late write is never seen").containsExactly("sequence-6");
    }

    @Test
    void aTransactionalSendThatTimedOutIsAbortedAndNeverSeenByACommittedReader() throws Exception {
        String topic = EventLog.newTopic("spike-s4-transactional", Map.of("cleanup.policy", "compact"));
        try (KafkaProducer<String, String> first = new KafkaProducer<>(producerSettings(
                Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "spike-s4-a-" + UUID.randomUUID())),
                new StringSerializer(), new StringSerializer());
             KafkaProducer<String, String> second = new KafkaProducer<>(producerSettings(
                Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "spike-s4-b-" + UUID.randomUUID())),
                new StringSerializer(), new StringSerializer())) {
            first.initTransactions();
            second.initTransactions();

            EventLog.pause();
            first.beginTransaction();
            assertThatThrownBy(() -> first.send(new ProducerRecord<>(topic, "account", "sequence-5"))
                    .get(5, TimeUnit.SECONDS)).as("the broker does not answer").isNotNull();
            EventLog.unpause();
            first.abortTransaction();

            second.beginTransaction();
            second.send(new ProducerRecord<>(topic, "account", "sequence-6")).get(10, TimeUnit.SECONDS);
            second.commitTransaction();
        }
        List<String> committed = read(topic, "read_committed");
        List<String> uncommitted = read(topic, "read_uncommitted");
        System.out.println("SPIKE-S4 transactional, read_committed: " + committed + "; read_uncommitted: "
                + uncommitted);
        assertThat(committed).containsExactly("sequence-6");
    }
}
