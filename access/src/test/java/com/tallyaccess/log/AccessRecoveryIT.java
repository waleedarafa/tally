package com.tallyaccess.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.AccessFactsMessage;
import com.tallyaccess.application.FactsStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The review of PR-6, finding 1: a failure while reading the log does not stop Access reading. When the store fails,
 * Access waits, goes back to its stored position, and reads again, so no message is skipped. If it cannot recover
 * within {@code tally.access.log.give-up-after}, it reports its liveness as broken, so the platform restarts it.
 */
@SpringBootTest(properties = {"tally.access.log.retry-after=200ms", "tally.access.log.give-up-after=3s"})
@Import(AccessRecoveryIT.FailingStore.class)
class AccessRecoveryIT {

    /** Access's store, which can fail the next applies, as a database that does not answer for a while. */
    @TestConfiguration
    static class FailingStore {

        static final AtomicInteger FAIL_NEXT_APPLIES = new AtomicInteger();

        @Bean
        @Primary
        FactsStore failingStore(@Qualifier("jdbcFactsStore") FactsStore store) {
            return new FactsStore() {
                @Override
                public Optional<com.tallyaccess.application.StoredFacts> find(UUID accountId) {
                    return store.find(accountId);
                }

                @Override
                public boolean applyIfNewer(AccessFactsMessage message, Instant appliedAt) {
                    if (FAIL_NEXT_APPLIES.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        throw new IllegalStateException("the database does not answer");
                    }
                    return store.applyIfNewer(message, appliedAt);
                }

                @Override
                public Map<Integer, Long> positions(String topic) {
                    return store.positions(topic);
                }

                @Override
                public void advance(String topic, int partition, long nextOffset) {
                    store.advance(topic, partition, nextOffset);
                }

                @Override
                public Optional<Instant> newestProducedAt() {
                    return store.newestProducedAt();
                }
            };
        }
    }

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

    @AfterEach
    void storeAnswers() {
        FailingStore.FAIL_NEXT_APPLIES.set(0);
    }

    @Autowired
    AccessCopy copy;

    @Autowired
    ApplicationAvailability availability;

    List<UUID> sendFive() {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID account = UUID.randomUUID();
            accounts.add(account);
            AccessTestSupport.sendCommitted(PRODUCER, topic, account, AccessTestSupport.facts(account, 1, "GOOD",
                    "ACTIVE"));
        }
        return accounts;
    }

    void awaitAllApplied(List<UUID> accounts) {
        await().atMost(Duration.ofSeconds(30)).until(() -> accounts.stream()
                .allMatch(account -> copy.answer(account).sequence() != null));
    }

    @Test
    void aFailedApplyIsReadAgainAndNoMessageIsSkipped() {
        awaitAllApplied(sendFive());

        FailingStore.FAIL_NEXT_APPLIES.set(1);
        List<UUID> accounts = sendFive();

        awaitAllApplied(accounts);
        assertThat(FailingStore.FAIL_NEXT_APPLIES.get()).as("the failure happened").isZero();
        assertThat(availability.getLivenessState()).isEqualTo(LivenessState.CORRECT);
    }

    @Test
    void aStoreThatKeepsFailingBreaksLivenessAndRecoveringRestoresIt() {
        awaitAllApplied(sendFive());

        FailingStore.FAIL_NEXT_APPLIES.set(Integer.MAX_VALUE);
        List<UUID> accounts = sendFive();
        await().atMost(Duration.ofSeconds(20)).until(() -> availability.getLivenessState() == LivenessState.BROKEN);

        FailingStore.FAIL_NEXT_APPLIES.set(0);
        awaitAllApplied(accounts);
        await().atMost(Duration.ofSeconds(10)).until(() -> availability.getLivenessState() == LivenessState.CORRECT);
    }
}
