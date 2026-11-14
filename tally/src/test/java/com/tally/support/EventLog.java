package com.tally.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The event log (ADR-012) as a container: one Kafka broker, in KRaft mode, for the whole test run. Each test
 * class creates its own topic with {@link #newTopic}, so no test meets a message it did not create (ADR-029).
 *
 * <p>Containers reach it on {@link #network()} at {@link #INTERNAL_BOOTSTRAP_SERVERS}; the test JVM and billing reach
 * it at {@link #bootstrapServers()}.
 *
 * <p>The cleaner checks for work every second instead of every 15 seconds (Kafka's default), so a test that
 * waits for compaction waits seconds, not minutes.
 */
public final class EventLog {

    /** Kafka's own image, at the version of the client Spring Boot 4.1.1 manages (kafka.version 4.2.1). */
    public static final String IMAGE = "apache/kafka:4.2.1";

    /** The network that other containers reach the broker on, for example Access (Slice 2 PR-7). */
    private static final Network NETWORK = Network.newNetwork();

    /** The broker's address for a container on {@link #network()}. */
    public static final String INTERNAL_BOOTSTRAP_SERVERS = "kafka:19092";

    private static final KafkaContainer CONTAINER = new KafkaContainer(IMAGE)
            .withNetwork(NETWORK)
            .withListener(INTERNAL_BOOTSTRAP_SERVERS)
            .withEnv("KAFKA_LOG_CLEANER_BACKOFF_MS", "1000");

    private EventLog() {
    }

    public static synchronized KafkaContainer container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    /**
     * Freezes the broker (docker pause): connections stay open and nothing is answered, as when the broker hangs or
     * the network drops. Its data and its port stay, so {@link #unpause()} brings back the same broker.
     */
    public static void pause() {
        container().getDockerClient().pauseContainerCmd(container().getContainerId()).exec();
    }

    public static void unpause() {
        container().getDockerClient().unpauseContainerCmd(container().getContainerId()).exec();
    }

    /** The network a container joins to reach the broker at {@link #INTERNAL_BOOTSTRAP_SERVERS}. */
    public static Network network() {
        container();
        return NETWORK;
    }

    public static String bootstrapServers() {
        return container().getBootstrapServers();
    }

    /**
     * The access feed's settings for the Tally instances on one database: this broker, and a compacted topic of the
     * database's own, {@code access-facts-<database>} (ADR-029: no test meets a message it did not create).
     */
    public static Map<String, String> feedSettings(String databaseUrl) {
        String database = databaseUrl.replaceFirst("^.*/([^/?]+)(\\?.*)?$", "$1");
        String topic = "access-facts-" + database;
        create(topic, Map.of("cleanup.policy", "compact"));
        return Map.of("tally.accessfeed.log.bootstrap-servers", bootstrapServers(),
                "tally.accessfeed.log.topic", topic);
    }

    /** A topic no other test uses: the given prefix and a random suffix, one partition. */
    public static String newTopic(String prefix, Map<String, String> config) {
        return newTopic(prefix, 1, config);
    }

    /** As {@link #newTopic(String, Map)}, with the given number of partitions. */
    public static String newTopic(String prefix, int partitions, Map<String, String> config) {
        String name = prefix + "-" + UUID.randomUUID();
        create(name, partitions, config);
        return name;
    }

    /** Creates a topic of one partition, unless it exists. */
    static void create(String name, Map<String, String> config) {
        create(name, 1, config);
    }

    static void create(String name, int partitions, Map<String, String> config) {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1).configs(config))).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    public static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    }

    /** A producer that waits for every replica's acknowledgement and never writes a message twice. */
    public static KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    /** Changes settings of an existing topic, for example to make compaction run while a test waits (spike S-2). */
    public static void configure(String topic, Map<String, String> config) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        List<AlterConfigOp> changes = config.entrySet().stream()
                .map(e -> new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET))
                .toList();
        try (Admin admin = admin()) {
            admin.incrementalAlterConfigs(Map.of(resource, changes)).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** A producer that sends as billing's relay does: each send a transaction of its own (ADR-033). */
    public static KafkaProducer<String, String> committedProducer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "test-relay-" + UUID.randomUUID());
        KafkaProducer<String, String> producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
        producer.initTransactions();
        return producer;
    }

    /** Sends one message in a transaction of its own and commits it; {@code partition} null lets the key decide. */
    public static void sendCommitted(KafkaProducer<String, String> producer, String topic, Integer partition,
            String key, String value) {
        producer.beginTransaction();
        try {
            producer.send(new ProducerRecord<>(topic, partition, key, value)).get();
            producer.commitTransaction();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** How many committed messages every partition of the topic holds now, read from the start. */
    public static int countCommitted(String topic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            int count = 0;
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < ends.get(tp))) {
                count += consumer.poll(Duration.ofMillis(500)).count();
            }
            return count;
        }
    }

    /** Sends and waits for the broker's acknowledgement. */
    public static void send(KafkaProducer<String, String> producer, String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** A consumer in the given group; {@code auto.offset.reset} is earliest and nothing is committed automatically. */
    public static KafkaConsumer<String, String> consumer(String group) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * Reads the whole topic from the start, as a rebuild does, with no group: assigns its one partition, seeks to
     * the beginning and polls until the end offset seen at the start is reached.
     */
    public static List<ConsumerRecord<String, String>> readAll(String topic) {
        return readAll(topic, "read_uncommitted");
    }

    /** As Access reads the log (ADR-033): committed messages only; an aborted transaction's message is never seen. */
    public static List<ConsumerRecord<String, String>> readCommitted(String topic) {
        return readAll(topic, "read_committed");
    }

    static List<ConsumerRecord<String, String>> readAll(String topic, String isolation) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (consumer.position(partition) < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }
}
