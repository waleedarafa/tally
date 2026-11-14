package com.tallyaccess.log;

import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.AccessFactsMessage;
import com.tallyaccess.application.AccessProperties;
import com.tallyaccess.application.Readiness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
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
 *   <li>A failure (the log, or Access's database) never ends the reading: Access waits, goes back to its stored
 *       position, and reads again, so nothing is skipped (review of PR-6, finding 1). Such a failure never changes
 *       liveness, since a restart cannot repair the log or the database; {@code tally.access.reading.failing.seconds}
 *       shows how long reading has failed, 0 while it reads. Liveness is broken only when the reading thread ends
 *       while Access runs, which a restart does repair (ADR-036).</li>
 *   <li>Before it reads the log, it asks Access's database whether the copy was complete once; if so, the instance
 *       is ready at once, also while the log is down. The first time the copy reaches the end seen at start, it
 *       writes that mark (ADR-036).</li>
 * </ul>
 */
@Component
class AccessLogConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccessLogConsumer.class);

    private final AccessCopy copy;
    private final Readiness readiness;
    private final AccessProperties.Log settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile boolean running;
    private volatile Instant failingSince;
    private KafkaConsumer<String, String> consumer;
    private Thread thread;

    AccessLogConsumer(AccessCopy copy, Readiness readiness, AccessProperties properties,
            ApplicationEventPublisher events, Clock clock, MeterRegistry meters) {
        this.copy = copy;
        this.readiness = readiness;
        this.settings = properties.log();
        this.events = events;
        this.clock = clock;
        Gauge.builder("tally.access.reading.failing.seconds", this, c -> c.failingFor().toMillis() / 1000.0)
                .baseUnit("seconds").strongReference(true).register(meters);
    }

    /** How long reading has failed; zero while it reads. */
    Duration failingFor() {
        Instant since = failingSince;
        return since == null ? Duration.ZERO : Duration.between(since, clock.instant());
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
        consumer.subscribe(List.of(settings.topic()), new MoveToStoredPosition());
        boolean markKnown = false;
        boolean marked = false;
        boolean endKnown = false;
        boolean goBack = false;
        Map<Integer, Long> written = Map.of();
        try {
            while (running) {
                try {
                    if (!markKnown) {
                        if (copy.completeOnce()) {
                            readiness.completeBefore();
                            marked = true;
                        }
                        markKnown = true;
                    }
                    if (!endKnown) {
                        readiness.endAtStart(endOfEachPartition());
                        endKnown = true;
                    }
                    if (goBack) {
                        moveToStoredPosition(consumer.assignment());
                        goBack = false;
                    }
                    List<AccessCopy.Read> reads = new ArrayList<>();
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                        reads.add(new AccessCopy.Read(record.partition(), record.offset(), parse(record)));
                    }
                    Map<Integer, Long> reached = consumer.assignment().stream()
                            .collect(Collectors.toMap(TopicPartition::partition, consumer::position));
                    // The positions are written only when something was read or a position moved (review of PR-6).
                    if (!reads.isEmpty() || !reached.equals(written)) {
                        copy.apply(settings.topic(), reads, reached);
                        written = reached;
                    }
                    if (!readiness.ready()) {
                        readiness.check(copy.positions(settings.topic()));
                    }
                    if (readiness.ready() && !marked) {
                        copy.markComplete();
                        marked = true;
                    }
                    if (failingSince != null) {
                        log.info("Access reads the log again, after {}", failingFor());
                        failingSince = null;
                    }
                } catch (WakeupException stopping) {
                    if (!running) {
                        break;
                    }
                } catch (RuntimeException e) {
                    // Nothing of this poll was stored, but the consumer's own position is already past it: go back to
                    // the stored position and read it again (review of PR-6, finding 1).
                    if (failingSince == null) {
                        failingSince = clock.instant();
                    }
                    log.warn("Reading the log failed; reading again from the stored position in {}",
                            settings.retryAfter(), e);
                    goBack = true;
                    written = Map.of();
                    pause(settings.retryAfter());
                }
            }
        } catch (Throwable ended) {
            log.error("The reading thread ends", ended);
            throw ended;
        } finally {
            if (running) {
                // Ended while Access runs: no answer gets fresher from here on, and a restart repairs it.
                log.error("Access no longer reads the log: liveness is broken");
                AvailabilityChangeEvent.publish(events, this, LivenessState.BROKEN);
            }
            consumer.close();
        }
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** ADR-035: each partition goes to Access's stored position, or to the beginning when none is stored. */
    private void moveToStoredPosition(Collection<TopicPartition> partitions) {
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
            moveToStoredPosition(partitions);
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
