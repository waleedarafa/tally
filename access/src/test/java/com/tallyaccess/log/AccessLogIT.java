package com.tallyaccess.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.application.AccessAnswer;
import com.tallyaccess.application.AccessAnswer.Reason;
import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.Readiness;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Access reads the event log into its own copy (Slice 2 PR-6; ADR-012, ADR-033, ADR-035), against a real broker and a
 * real PostgreSQL, with messages sent as billing's relay sends them: one transaction each.
 */
@SpringBootTest
class AccessLogIT {

    static String topic;
    static final KafkaProducer<String, String> PRODUCER = AccessTestSupport.producer();

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        topic = AccessTestSupport.isolate(registry);
    }

    @AfterAll
    static void close() {
        PRODUCER.close();
    }

    @Autowired
    AccessCopy copy;

    @Autowired
    Readiness readiness;

    @Autowired
    AccessLogConsumer consumer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    void send(UUID account, long sequence, String standing, String... states) {
        AccessTestSupport.sendCommitted(PRODUCER, topic, account, AccessTestSupport.facts(account, sequence, standing,
                states));
    }

    AccessAnswer awaitSequence(UUID account, long sequence) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Long seen = copy.answer(account).sequence();
            return seen != null && seen == sequence;
        });
        return copy.answer(account);
    }

    /** Lets the consumer read everything sent so far: its stored position reaches the end of the log. */
    void awaitCaughtUp() {
        long end = AccessTestSupport.endOffset(topic);
        await().atMost(Duration.ofSeconds(20)).until(() -> copy.positions(topic).getOrDefault(0, 0L) >= end);
    }

    @Test
    void aMessageIsAppliedAndAnswered() {
        UUID account = UUID.randomUUID();
        send(account, 1, "GOOD", "ACTIVE");

        AccessAnswer answer = awaitSequence(account, 1);
        assertThat(answer.canWatch()).isTrue();
        assertThat(answer.reason()).isEqualTo(Reason.ACTIVE_SOURCE);
        assertThat(readiness.ready()).isTrue();
    }

    /** AT-21's case at the consumer: 6, then 5, then 6 again; the answer stays on 6 (CLAUDE.md rule 15). */
    @Test
    void anOlderOrRepeatedMessageChangesNothing() {
        UUID account = UUID.randomUUID();
        send(account, 6, "GOOD", "ACTIVE");
        awaitSequence(account, 6);

        send(account, 5, "BLOCKED", "ACTIVE");
        send(account, 6, "BLOCKED", "ACTIVE");
        awaitCaughtUp();

        AccessAnswer answer = copy.answer(account);
        assertThat(answer.sequence()).isEqualTo(6);
        assertThat(answer.reason()).as("the first 6 stays: the older 5 and the repeated 6 are not applied")
                .isEqualTo(Reason.ACTIVE_SOURCE);
    }

    /** ADR-033, decided on PR-5: a message billing's relay did not commit is never applied. */
    @Test
    void aMessageInAnAbortedTransactionIsNeverApplied() {
        UUID account = UUID.randomUUID();
        send(account, 1, "GOOD", "ACTIVE");
        awaitSequence(account, 1);

        // The aborted one has the higher sequence: applied, it would block the account and hide the committed 3.
        AccessTestSupport.sendAborted(PRODUCER, topic, account, AccessTestSupport.facts(account, 5, "BLOCKED", "ACTIVE"));
        send(account, 3, "OVERDUE", "ACTIVE");

        assertThat(awaitSequence(account, 3).reason()).isEqualTo(Reason.ACTIVE_SOURCE);
        assertThat(jdbc.queryForObject("SELECT standing FROM access.facts WHERE account_id = ?", String.class,
                account)).isEqualTo("OVERDUE");
    }

    /** ADR-033: a value Access does not know never takes access away, and is counted. */
    @Test
    void valuesAccessDoesNotKnowAreCountedAndNeverTakeAccessAway() {
        double before = notKnown("standing");
        UUID account = UUID.randomUUID();
        AccessTestSupport.sendCommitted(PRODUCER, topic, account, "{\"schemaVersion\":2,\"accountId\":\"" + account
                + "\",\"sequence\":4,\"producedAt\":\"2026-11-13T06:00:00Z\",\"standing\":\"SUSPENDED\","
                + "\"graceEndsAt\":\"2026-11-20T06:00:00Z\",\"sources\":[{\"kind\":\"PARTNER_BUNDLE\",\"id\":\""
                + UUID.randomUUID() + "\",\"state\":\"PAUSED\"}]}");

        AccessAnswer answer = awaitSequence(account, 4);
        assertThat(answer.canWatch()).isTrue();
        assertThat(notKnown("standing")).isEqualTo(before + 1);
        assertThat(notKnown("sources.kind")).isPositive();
        assertThat(notKnown("sources.state")).isPositive();
    }

    double notKnown(String field) {
        var counter = meters.find("tally.access.values.not_known").tag("field", field).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aMessageAccessCannotReadIsPassedOverAndCounted() {
        UUID account = UUID.randomUUID();
        double before = meters.counter("tally.access.unreadable").count();
        PRODUCER.beginTransaction();
        PRODUCER.send(new ProducerRecord<>(topic, account.toString(), "not json"));
        PRODUCER.commitTransaction();
        send(account, 1, "GOOD", "ACTIVE");

        awaitSequence(account, 1);
        assertThat(meters.counter("tally.access.unreadable").count()).isEqualTo(before + 1);
    }

    /**
     * ADR-035: the position is Access's own, in its database. Emptied, with the same consumer group and no operator
     * step, Access reads the log from the start and holds the same answers again (spike S-3: the broker's position
     * would not have).
     */
    @Test
    void anEmptiedDatabaseIsRebuiltFromTheStartWithTheOldGroup() {
        UUID account = UUID.randomUUID();
        send(account, 1, "GOOD", "ACTIVE");
        send(account, 2, "BLOCKED", "ACTIVE");
        awaitSequence(account, 2);

        consumer.stop();
        jdbc.update("DELETE FROM access.facts");
        jdbc.update("DELETE FROM access.log_position");
        assertThat(copy.answer(account).reason()).isEqualTo(Reason.NOT_KNOWN);
        consumer.start();

        AccessAnswer rebuilt = awaitSequence(account, 2);
        assertThat(rebuilt.reason()).isEqualTo(Reason.ACCOUNT_BLOCKED);
    }
}
