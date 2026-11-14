package com.tallyaccess;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Access's tests run against a real PostgreSQL and a real broker, one of each per test run. Each test class gets a
 * database and a topic of its own (ADR-029: no test meets a row or a message it did not create).
 */
public final class AccessTestSupport {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private AccessTestSupport() {
    }

    /** A database and a compacted topic of the test's own, and the settings that point Access at them. */
    public static String isolate(DynamicPropertyRegistry registry) {
        Map<String, String> settings = isolated();
        settings.forEach((key, value) -> registry.add(key, () -> value));
        return settings.get("tally.access.log.topic");
    }

    /** As {@link #isolate}, as settings for an instance a test starts itself. */
    public static Map<String, String> isolated() {
        String name = "access_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            c.createStatement().execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        String topic = "access-facts-" + name;
        createTopic(topic);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.datasource.url", POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
        settings.put("spring.datasource.username", POSTGRES.getUsername());
        settings.put("spring.datasource.password", POSTGRES.getPassword());
        settings.put("tally.access.log.bootstrap-servers", KAFKA.getBootstrapServers());
        settings.put("tally.access.log.topic", topic);
        settings.put("tally.access.log.group-id", "tally-access-" + name);
        return settings;
    }

    static void createTopic(String topic) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
                    .all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The end of partition 0, read committed. */
    public static long endOffset(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"),
                new StringDeserializer(), new StringDeserializer())) {
            TopicPartition partition = new TopicPartition(topic, 0);
            return consumer.endOffsets(List.of(partition)).get(partition);
        }
    }

    /** A producer as billing's relay is: transactional (ADR-033). */
    public static KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.TRANSACTIONAL_ID_CONFIG, "access-test-" + UUID.randomUUID(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new StringSerializer());
        producer.initTransactions();
        return producer;
    }

    /** Sends one message in a transaction and commits it, as billing's relay does. */
    public static void sendCommitted(KafkaProducer<String, String> producer, String topic, UUID account,
            String message) {
        send(producer, topic, account, message, true);
    }

    /** Sends one message in a transaction and aborts it, as billing's relay does when the broker does not answer. */
    public static void sendAborted(KafkaProducer<String, String> producer, String topic, UUID account,
            String message) {
        send(producer, topic, account, message, false);
    }

    private static void send(KafkaProducer<String, String> producer, String topic, UUID account, String message,
            boolean commit) {
        producer.beginTransaction();
        try {
            producer.send(new ProducerRecord<>(topic, account.toString(), message)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (commit) {
            producer.commitTransaction();
        } else {
            producer.abortTransaction();
        }
    }

    /** A version 1 message. */
    public static String facts(UUID account, long sequence, String standing, String... sourceStates) {
        StringBuilder sources = new StringBuilder();
        for (String state : sourceStates) {
            if (!sources.isEmpty()) {
                sources.append(',');
            }
            sources.append("{\"kind\":\"SUBSCRIPTION\",\"id\":\"").append(UUID.randomUUID()).append("\",\"state\":\"")
                    .append(state).append("\"}");
        }
        return "{\"schemaVersion\":1,\"accountId\":\"" + account + "\",\"sequence\":" + sequence
                + ",\"producedAt\":\"2026-11-13T06:00:0" + (sequence % 10) + "Z\",\"standing\":\"" + standing
                + "\",\"sources\":[" + sources + "]}";
    }
}
