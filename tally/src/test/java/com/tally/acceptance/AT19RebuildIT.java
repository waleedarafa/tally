package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.EventLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * AT-19. Rebuild (ADR-012, ADR-035, ADR-036). Empty Access's database and start Access with its <b>old</b> consumer
 * group: no reset of the group, and no other step in the broker. Pass when Access reads from the start, is "not ready"
 * until it has caught up, and then every answer equals the answer before. Run once on a topic where compaction has run
 * (the settings of spike S-2), and once where it has not. Also: stop one of two instances in the middle of a load;
 * pass when the other takes its partitions and no message is lost.
 *
 * <p>The topic has three partitions, so two instances share it. The messages are written as billing's relay writes
 * them, one transaction each: billing cannot be made to write a history of many changes per account quickly, and a
 * history is what compaction removes. "Empty the database" empties the facts, the positions and the ADR-036 mark.
 * "Not ready until caught up" is checked as what it protects: before the instance is ready, every answer is a 503,
 * never a NOT_KNOWN or an older answer for a paying household.
 */
class AT19RebuildIT {

    static final int ACCOUNTS = 300;
    static final int PARTITIONS = 3;

    final List<AccessService> deployments = new ArrayList<>();
    KafkaProducer<String, String> producer;

    @AfterEach
    void stop() {
        if (producer != null) {
            producer.close();
        }
        deployments.forEach(AccessService::close);
    }

    String newTopic() {
        return EventLog.newTopic("at19", PARTITIONS, Map.of("cleanup.policy", "compact"));
    }

    AccessService deployment(String topic) {
        AccessService access = AccessService.on(topic);
        deployments.add(access);
        return access;
    }

    /** The account's facts at a sequence of 1 to 3; at 3 one of the three answers, by the account's place. */
    static String facts(UUID account, int place, int sequence) {
        if (sequence < 3) {
            return AccessFacts.json(account, sequence, sequence == 1 ? "GOOD" : "BLOCKED", "ACTIVE");
        }
        return switch (place % 3) {
            case 0 -> AccessFacts.json(account, 3, "GOOD", "ACTIVE");
            case 1 -> AccessFacts.json(account, 3, "BLOCKED", "ACTIVE");
            default -> AccessFacts.json(account, 3, "GOOD");
        };
    }

    List<UUID> sendHistory(String topic) {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(UUID.randomUUID());
        }
        for (int sequence = 1; sequence <= 3; sequence++) {
            for (int i = 0; i < ACCOUNTS; i++) {
                UUID account = accounts.get(i);
                EventLog.sendCommitted(producer, topic, null, account.toString(), facts(account, i, sequence));
            }
        }
        return accounts;
    }

    static boolean allAtSequence(Instance instance, List<UUID> accounts, long sequence) {
        return accounts.stream().allMatch(account -> instance.status(account) == 200
                && Long.valueOf(sequence).equals(number(instance.answer(account).get("sequence"))));
    }

    static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    @Test
    void aRebuildOnATopicWhereCompactionHasNotRunEndsOnTheSameAnswers() {
        rebuild(false);
    }

    @Test
    void aRebuildOnATopicWhereCompactionHasRunEndsOnTheSameAnswers() {
        rebuild(true);
    }

    void rebuild(boolean compacted) {
        String topic = newTopic();
        AccessService access = deployment(topic);
        producer = EventLog.committedProducer();
        Instance first = access.start("at19-first");
        List<UUID> accounts = sendHistory(topic);
        int sent = 3 * ACCOUNTS;

        if (compacted) {
            // Spike S-2: the settings that make the cleaner run within seconds, then one more message in each
            // partition, more than segment.ms later, so the segments holding the history close.
            EventLog.configure(topic, Map.of("segment.ms", "100", "min.cleanable.dirty.ratio", "0.01",
                    "min.compaction.lag.ms", "0", "delete.retention.ms", "100"));
            sleep(300);
            for (int partition = 0; partition < PARTITIONS; partition++) {
                UUID roll = UUID.randomUUID();
                accounts.add(roll);
                EventLog.sendCommitted(producer, topic, partition, roll.toString(), AccessFacts.json(roll, 3, "GOOD",
                        "ACTIVE"));
                sent++;
            }
            int total = sent;
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> EventLog.countCommitted(topic) < total);
            System.out.printf("AT-19: compaction ran: %d of %d messages left%n", EventLog.countCommitted(topic), sent);
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> allAtSequence(first, accounts, 3));
        Map<UUID, Map<String, Object>> before = new LinkedHashMap<>();
        accounts.forEach(account -> before.put(account, first.answer(account)));

        access.stop(first);
        access.emptyDatabase();
        Instance rebuilt = access.start("at19-rebuilt");

        // Until it is ready, every answer is a 503: never NOT_KNOWN, never an older answer.
        UUID watched = accounts.getFirst();
        int refused = 0;
        while (!rebuilt.ready()) {
            int status = rebuilt.status(watched);
            if (status == 503) {
                refused++;
            } else {
                assertThat(status).as("before ready").isEqualTo(200);
                assertThat(rebuilt.answer(watched)).as("an answer before ready").isEqualTo(before.get(watched));
            }
        }
        for (UUID account : accounts) {
            assertThat(rebuilt.answer(account)).as("account %s after the rebuild", account)
                    .isEqualTo(before.get(account));
        }
        System.out.printf("AT-19: rebuilt with the old group (%s), %d accounts equal; %d answers refused (503)"
                + " while not ready%n", compacted ? "compacted" : "not compacted", accounts.size(), refused);
    }

    @Test
    void anInstanceStoppedInTheMiddleOfALoadLosesNoMessage() throws Exception {
        String topic = newTopic();
        AccessService access = deployment(topic);
        producer = EventLog.committedProducer();
        Instance one = access.start("at19-one");
        Instance other = access.start("at19-other");

        CountDownLatch aThirdSent = new CountDownLatch(1);
        CompletableFuture<List<UUID>> load = CompletableFuture.supplyAsync(() -> {
            List<UUID> accounts = new ArrayList<>();
            for (int i = 0; i < ACCOUNTS; i++) {
                accounts.add(UUID.randomUUID());
            }
            int sends = 0;
            for (int sequence = 1; sequence <= 3; sequence++) {
                for (int i = 0; i < ACCOUNTS; i++) {
                    UUID account = accounts.get(i);
                    EventLog.sendCommitted(producer, topic, null, account.toString(), facts(account, i, sequence));
                    if (++sends == ACCOUNTS) {
                        aThirdSent.countDown();
                    }
                }
            }
            return accounts;
        });
        assertThat(aThirdSent.await(60, TimeUnit.SECONDS)).isTrue();
        access.stop(one);
        List<UUID> accounts = load.get(120, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                .until(() -> allAtSequence(other, accounts, 3));
        System.out.printf("AT-19: one of two instances stopped after %d of %d sends; the other applied all %d"
                + " accounts at sequence 3%n", ACCOUNTS, 3 * ACCOUNTS, ACCOUNTS);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
