package com.tallyaccess.log;

import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.AccessFactsMessage;
import com.tallyaccess.application.AccessProperties;
import com.tallyaccess.application.Readiness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the event log into Access's copy (ADR-012, ADR-033, ADR-035), on one thread of its own.
 * <ul>
 *   <li>Committed messages only ({@code isolation.level=read_committed}): billing commits a message only once the
 *       broker has acknowledged it, so a send billing gave up on is never seen (ADR-033, spike S-4).</li>
 *   <li>The position is Access's own (ADR-035). The instances join one group, so the broker shares the partitions;
 *       when an instance gets a partition it moves to the position stored in Access's database, or to the beginning
 *       when none is stored. It never commits a position to the broker and never uses the broker's. So an emptied
 *       database reads the log from the start, with no operator step (spike S-3).</li>
 *   <li>After each poll, the facts and the positions reached are written in one transaction. The position is the
 *       consumer's, which also steps over the log's transaction markers, so the end of the log can be reached.</li>
 * </ul>
 */
@Component
class AccessLogConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccessLogConsumer.class);

    private final AccessCopy copy;
    private final Readiness readiness;
    private final AccessProperties.Log settings;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile boolean running;
    private KafkaConsumer<String, String> consumer;
    private Thread thread;

    AccessLogConsumer(AccessCopy copy, Readiness readiness, AccessProperties properties) {
        this.copy = copy;
        this.readiness = readiness;
        this.settings = properties.log();
    }

    @Override
    public synchronized void start() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, settings.groupId(),
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
        running = true;
        thread = new Thread(this::run, "access-log-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            readiness.endAtStart(endOfEachPartition());
            consumer.subscribe(List.of(settings.topic()), new MoveToStoredPosition());
            while (running) {
                List<AccessCopy.Read> reads = new ArrayList<>();
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    reads.add(new AccessCopy.Read(record.partition(), record.offset(), parse(record)));
                }
                Map<Integer, Long> reached = consumer.assignment().stream()
                        .collect(Collectors.toMap(TopicPartition::partition, consumer::position));
                if (!reads.isEmpty() || !reached.isEmpty()) {
                    copy.apply(settings.topic(), reads, reached);
                }
                readiness.check(copy.positions(settings.topic()));
            }
        } catch (WakeupException stopping) {
            // stop() woke the consumer up.
        } catch (RuntimeException e) {
            log.error("Access stopped reading the log", e);
        } finally {
            consumer.close();
        }
    }

    /** The end of each partition, read committed, now. */
    private Map<Integer, Long> endOfEachPartition() {
        List<TopicPartition> partitions = consumer.partitionsFor(settings.topic()).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        Map<Integer, Long> ends = new HashMap<>();
        consumer.endOffsets(partitions).forEach((partition, end) -> ends.put(partition.partition(), end));
        return ends;
    }

    private AccessFactsMessage parse(ConsumerRecord<String, String> record) {
        try {
            return json.readValue(record.value(), AccessFactsMessage.class);
        } catch (RuntimeException unreadable) {
            log.warn("Message at {}-{}:{} is not AccessFacts", record.topic(), record.partition(), record.offset(),
                    unreadable);
            return null;
        }
    }

    /** ADR-035: a partition given to this instance starts at Access's stored position, or at the beginning. */
    private final class MoveToStoredPosition implements ConsumerRebalanceListener {

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            Map<Integer, Long> stored = copy.positions(settings.topic());
            List<TopicPartition> unread = new ArrayList<>();
            for (TopicPartition partition : partitions) {
                Long next = stored.get(partition.partition());
                if (next == null) {
                    unread.add(partition);
                } else {
                    consumer.seek(partition, next);
                }
            }
            if (!unread.isEmpty()) {
                consumer.seekToBeginning(unread);
            }
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            // Nothing to commit: the position is already in Access's database, with the facts.
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
