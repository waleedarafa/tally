package com.tally.accessfeed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.platform.Outcome;
import com.tally.platform.QueueItem;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import com.tally.support.EventLog;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 2 PR-5 (ADR-033 decision 2): the relay sends an account's current facts to the event log, a real broker in a
 * container, on this class's own topic (ADR-029). Polling is off: each test runs the relay's handler itself, on the
 * item the feed queued, so it can stop between the send and its record as a crash would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AccessFactsRelayIT.DyingLog.class)
@TestPropertySource(properties = {"tally.accessfeed.log.send-timeout=2s",
        "tally.accessfeed.log.transaction-timeout=3s", "tally.accessfeed.relay.retry-after=30s"})
class AccessFactsRelayIT extends AbstractIntegrationTest {

    /** The real log, which can die right after its next send acknowledged: the record of the send never happens. */
    @TestConfiguration
    static class DyingLog {

        static final AtomicBoolean DIE_AFTER_NEXT_SEND = new AtomicBoolean();

        @Bean
        @Primary
        AccessFactsLog dyingLog(@Qualifier("kafkaAccessFactsLog") AccessFactsLog kafka) {
            return (accountId, message) -> {
                kafka.send(accountId, message);
                if (DIE_AFTER_NEXT_SEND.getAndSet(false)) {
                    throw new IllegalStateException("the instance dies after the send, before its record");
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Value("${tally.accessfeed.log.topic}")
    String topic;

    @Autowired
    AccessFactsRelay relay;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountApi accounts;

    @Autowired
    AccessFeedHealth health;

    static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void brokerBack() {
        DyingLog.DIE_AFTER_NEXT_SEND.set(false);
        try {
            EventLog.unpause();
        } catch (RuntimeException notPaused) {
            // It was not paused.
        }
    }

    /** An account whose facts changed once: a subscription started. Waits for the feed to queue the change. */
    UUID changedAccount() {
        UUID account = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> events.publishEvent(new SubscriptionChanged(UUID.randomUUID(),
                clock.instant(), account, UUID.randomUUID(), "STANDARD", 1, SubscriptionChangeType.STARTED)));
        await().atMost(Duration.ofSeconds(15)).until(() -> queued(account) > 0);
        return account;
    }

    long queued(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM accessfeed.relay_queue WHERE payload = ?", Long.class,
                account.toString());
    }

    QueueItem itemOf(UUID account) {
        return jdbc.queryForObject("SELECT id, queue_key FROM accessfeed.relay_queue WHERE payload = ? ORDER BY id"
                + " LIMIT 1", (rs, n) -> new QueueItem(rs.getLong("id"), rs.getString("queue_key"), clock.instant(),
                        account.toString(), 1), account.toString());
    }

    long sent(UUID account) {
        return jdbc.queryForObject("SELECT sent_sequence FROM accessfeed.account_facts WHERE account_id = ?",
                Long.class, account);
    }

    /** The account's messages, as Access reads them: committed only (ADR-033). */
    List<JsonNode> messagesFor(UUID account) {
        return EventLog.readCommitted(topic).stream().filter(r -> r.key().equals(account.toString()))
                .map(ConsumerRecord::value).map(JSON::readTree).toList();
    }

    /** Every message the topic holds for the account, aborted ones included. */
    List<Long> everySequenceWrittenFor(UUID account) {
        return EventLog.readAll(topic).stream().filter(r -> r.key().equals(account.toString()))
                .map(r -> JSON.readTree(r.value()).get("sequence").asLong()).toList();
    }

    @Test
    void aChangeReachesTheLogWithTheCurrentFactsAndIsRecordedAsSent() {
        UUID account = changedAccount();
        assertThat(jdbc.queryForObject("SELECT queue_key FROM accessfeed.relay_queue WHERE payload = ?", String.class,
                account.toString())).isEqualTo(account + ":1");

        assertThat(relay.handle(itemOf(account))).isEqualTo(Outcome.done());

        assertThat(sent(account)).isEqualTo(1);
        assertThat(messagesFor(account)).singleElement().satisfies(message -> {
            assertThat(message.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(message.get("accountId").asString()).isEqualTo(account.toString());
            assertThat(message.get("sequence").asLong()).isEqualTo(1);
            assertThat(message.get("standing").asString()).isEqualTo("GOOD");
            assertThat(message.get("sources")).hasSize(1);
        });
    }

    @Test
    void factsAlreadyInTheLogAreNotSentAgain() {
        UUID account = changedAccount();
        relay.handle(itemOf(account));

        assertThat(relay.handle(itemOf(account))).isEqualTo(Outcome.done());

        assertThat(messagesFor(account)).hasSize(1);
    }

    /** Brief, PR-5: a crash after the send and before its record sends the same sequence again. That is correct. */
    @Test
    void aCrashAfterTheSendSendsTheSameSequenceAgain() {
        UUID account = changedAccount();
        DyingLog.DIE_AFTER_NEXT_SEND.set(true);

        assertThatThrownBy(() -> relay.handle(itemOf(account))).hasMessageContaining("dies after the send");
        assertThat(sent(account)).as("the record rolled back with the crash").isZero();

        assertThat(relay.handle(itemOf(account))).isEqualTo(Outcome.done());
        await().atMost(Duration.ofSeconds(15)).until(() -> messagesFor(account).size() == 2);
        assertThat(messagesFor(account)).extracting(m -> m.get("sequence").asLong()).containsExactly(1L, 1L);
        assertThat(sent(account)).isEqualTo(1);
    }

    /**
     * Brief, PR-5: with the broker down, sign-up still answers 201; the change waits, and the feed's backlog shows in
     * {@code tally.accessfeed.unsent.oldest_age_seconds}. When the broker is back, the change reaches the log.
     */
    @Test
    void withTheBrokerDownSignUpAnswers201AndTheBacklogShowsUntilItIsBack() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        EventLog.pause();

        ResponseEntity<String> signUp = RestClient.create("http://localhost:" + port).post().uri("/v1/subscriptions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", "tok_relay"))
                .retrieve().toEntity(String.class);
        assertThat(signUp.getStatusCode().value()).isEqualTo(201);
        await().atMost(Duration.ofSeconds(15)).until(() -> queued(account) > 0);

        Outcome down = relay.handle(itemOf(account));
        assertThat(down).isEqualTo(Outcome.retryAt(clock.instant().plus(Duration.ofSeconds(30))));
        assertThat(sent(account)).isZero();
        clock.advance(Duration.ofMinutes(2));
        assertThat(health.oldestUnsentAge()).isGreaterThanOrEqualTo(Duration.ofMinutes(2));

        EventLog.unpause();
        assertThat(relay.handle(itemOf(account))).isEqualTo(Outcome.done());
        assertThat(sent(account)).isEqualTo(1);
        // Committed only: the send that timed out is never seen, even if it reached the log (spike S-4).
        await().atMost(Duration.ofSeconds(15)).until(() -> messagesFor(account).size() == 1);
        assertThat(health.oldestUnsentAge()).isEqualTo(Duration.ZERO);
    }

    /**
     * The gap through the relay (spike S-4, decision on PR-5): a send the broker does not acknowledge, then a newer
     * change, sent once the broker is back by a new producer, as another instance would. Whether the first send's
     * bytes still reach the log is up to the client; as Access reads the log, committed only, the account's messages
     * are the newer sequence alone. {@code SpikeS4UnacknowledgedSendIT} drives the late write itself.
     */
    @Test
    void aSendThatTimedOutNeverEndsTheCommittedLogBeforeANewerOne() {
        UUID account = changedAccount();
        EventLog.pause();
        assertThat(relay.handle(itemOf(account))).as("sequence 1, not acknowledged")
                .isInstanceOf(Outcome.RetryAt.class);

        transactions.executeWithoutResult(tx -> events.publishEvent(new com.tally.account.StandingChanged(
                UUID.randomUUID(), clock.instant(), account, 1L, com.tally.account.Standing.BLOCKED)));
        await().atMost(Duration.ofSeconds(15)).until(() -> queued(account) > 1);
        EventLog.unpause();
        assertThat(relay.handle(itemOf(account))).isEqualTo(Outcome.done());
        assertThat(sent(account)).isEqualTo(2);

        await().atMost(Duration.ofSeconds(15)).until(() -> !messagesFor(account).isEmpty()
                && messagesFor(account).getLast().get("sequence").asLong() == 2);
        List<Long> committed = messagesFor(account).stream().map(m -> m.get("sequence").asLong()).toList();
        System.out.println("GAP committed: " + committed + "; every message written: "
                + everySequenceWrittenFor(account));
        assertThat(committed).containsExactly(2L);
    }
}
