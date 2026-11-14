package com.tally.accessfeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.support.EventLog;
import com.tally.support.TallyInstances;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 2 PR-5, the property of decision 2 (ADR-033, CLAUDE.md rule 15): two billing instances on one database, many
 * changes to few accounts, the broker stopped (frozen) and started in the middle. At the end, for every account, the
 * last message in the topic, read as Access reads it (committed only), has the account's current sequence, and no
 * message for an account follows one with a higher sequence. Prints ORDER lines.
 */
class AccessFeedOrderIT {

    static final int ACCOUNTS = 4;
    static final int CHANGES = 30;
    static final Standing[] CYCLE = {Standing.OVERDUE, Standing.BLOCKED, Standing.GOOD};

    final TallyInstances instances = new TallyInstances();

    @AfterEach
    void stop() {
        try {
            EventLog.unpause();
        } catch (RuntimeException notPaused) {
            // It was not paused.
        }
        instances.close();
    }

    @Test
    void theLastCommittedMessageOfEveryAccountHasItsCurrentSequenceAndNoneFollowsAHigherOne() throws Exception {
        String[] settings = {"tally.platform.polling-enabled=true", "tally.platform.poll-interval=50ms",
                "tally.accessfeed.relay.workers=2", "tally.accessfeed.relay.retry-after=0s",
                "tally.accessfeed.log.send-timeout=2s", "tally.accessfeed.log.transaction-timeout=3s"};
        ConfigurableApplicationContext a = instances.start("feed-a", Object.class, settings);
        instances.start("feed-b", Object.class, settings);
        String topic = a.getEnvironment().getProperty("tally.accessfeed.log.topic");
        AccountApi accountApi = a.getBean(AccountApi.class);
        AccountStanding standing = a.getBean(AccountStanding.class);
        JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);

        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(accountApi.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id());
        }
        for (int change = 0; change < CHANGES; change++) {
            if (change == CHANGES / 3) {
                EventLog.pause();
                System.out.println("ORDER broker stopped after " + change + " changes per account");
            }
            if (change == 2 * CHANGES / 3) {
                EventLog.unpause();
                System.out.println("ORDER broker started after " + change + " changes per account");
            }
            for (UUID account : accounts) {
                standing.set(account, CYCLE[change % CYCLE.length], "order test");
            }
            Thread.sleep(40);
        }

        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(500)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM accessfeed.account_facts WHERE sequence > sent_sequence", Long.class) == 0
                && jdbc.queryForObject("SELECT count(*) FROM accessfeed.account_facts", Long.class) == ACCOUNTS);

        JsonMapper json = JsonMapper.builder().build();
        Map<String, List<Long>> sequences = new HashMap<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            sequences.clear();
            for (ConsumerRecord<String, String> record : EventLog.readCommitted(topic)) {
                sequences.computeIfAbsent(record.key(), k -> new ArrayList<>())
                        .add(json.readTree(record.value()).get("sequence").asLong());
            }
            return accounts.stream().allMatch(account -> sequences.containsKey(account.toString())
                    && sequences.get(account.toString()).getLast() == current(jdbc, account));
        });
        List<Map<String, Object>> claimedBy = jdbc.queryForList("SELECT claimed_by, count(*) AS items"
                + " FROM accessfeed.relay_queue WHERE claimed_by IS NOT NULL GROUP BY claimed_by");
        System.out.println("ORDER relay items per instance: " + claimedBy);
        assertThat(claimedBy).as("both instances relayed").hasSize(2);

        for (UUID account : accounts) {
            List<Long> seen = sequences.get(account.toString());
            System.out.println("ORDER account " + account + ": current " + current(jdbc, account) + ", committed "
                    + seen);
            assertThat(seen.getLast()).isEqualTo(current(jdbc, account));
            assertThat(seen).as("no message follows one with a higher sequence").isSorted();
            // The feed counts only the changes it applied: an older standing event that arrives after a newer one is
            // ignored. It ends on the account's real standing all the same.
            assertThat(jdbc.queryForObject("SELECT standing FROM accessfeed.account_facts WHERE account_id = ?",
                    String.class, account)).isEqualTo(accountApi.get(account).orElseThrow().standing().name());
        }
    }

    static long current(JdbcTemplate jdbc, UUID account) {
        return jdbc.queryForObject("SELECT sequence FROM accessfeed.account_facts WHERE account_id = ?", Long.class,
                account);
    }
}
