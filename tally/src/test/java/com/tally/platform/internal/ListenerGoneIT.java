package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-028, before a listener is removed (Slice 2 PR-9): a publication whose listener no longer exists must show.
 * This test stores one, as a release that still had the listener would have left it, and runs Tally's resubmission.
 *
 * <p>What Spring Modulith does with it is pinned here, so an upgrade that changes it fails the build: resubmission
 * finds no listener for it, marks it {@code FAILED} and leaves it incomplete; nothing ever runs it. {@code tally.events.listener_gone} counts the
 * incomplete publications whose listener this release does not have, so the alert {@code EventListenerGone} fires;
 * its runbook's query names the listener.
 */
@TestPropertySource(properties = "tally.test.context=listener-gone")
class ListenerGoneIT extends AbstractIntegrationTest {

    static final String GONE = "com.tally.gone.application.GoneService.on(com.tally.subscription.SubscriptionChanged)";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    EventResubmission resubmission;

    @Autowired
    MeterRegistry meters;

    UUID store(String listener, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        String event = json.writeValueAsString(new SubscriptionChanged(UUID.randomUUID(), publishedAt,
                UUID.randomUUID(), UUID.randomUUID(), "BASIC", 1, SubscriptionChangeType.STARTED));
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " status, completion_attempts) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', 1)",
                id, listener, SubscriptionChanged.class.getName(), event, publishedAt.atOffset(ZoneOffset.UTC));
        return id;
    }

    double gone() {
        return meters.get("tally.events.listener_gone").gauge().value();
    }

    @Test
    void aPublicationWhoseListenerIsGoneStaysIncompleteAndIsCounted() {
        double before = gone();
        UUID publication = store(GONE, clock.instant().minus(Duration.ofHours(1)));

        resubmission.run();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT completion_date, status FROM event_publication WHERE id = ?", publication);
        System.out.println("LISTENER-GONE after resubmission: " + row);
        assertThat(row.get("completion_date")).as("Spring Modulith leaves it incomplete").isNull();
        assertThat(row.get("status")).as("and marks it failed").isEqualTo("FAILED");
        assertThat(gone()).isEqualTo(before + 1);
    }

    @Test
    void anIncompletePublicationOfAListenerThatExistsIsNotCounted() {
        double before = gone();
        store("com.tally.accessfeed.application.AccessFeedListeners.on(com.tally.subscription.SubscriptionChanged)",
                clock.instant());

        assertThat(gone()).isEqualTo(before);
    }
}
