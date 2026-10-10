package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.platform.TestClock;
import com.tally.support.TallyInstances;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.transaction.support.TransactionTemplate;
import spike.modulith.SpikeConfiguration;
import spike.modulith.SpikeEvent;
import spike.modulith.SpikeListener;
import spike.modulith.SpikeListener.Behaviour;

/**
 * Spike S-1 (Slice 1R brief, ADR-016): can Spring Modulith 2.1.1's resubmission be driven one
 * publication at a time with a predicate, catching per row, so that one unreadable publication never
 * blocks the rest (first-run finding 1)? Each experiment leaves two readable FAILED publications and one
 * unreadable one, then tries one way of resubmitting. Prints its evidence with the prefix SPIKE-S1 and
 * asserts what was observed. Findings: docs/briefs/slice-1r-spikes.md.
 */
class SpikeS1ResubmitOneAtATimeIT {

    final TallyInstances instances = new TallyInstances();
    final List<UUID> unreadable = new ArrayList<>();
    ConfigurableApplicationContext context;

    @BeforeEach
    void start() {
        SpikeListener.RELEASE.drainPermits();
        context = instances.start("s1", SpikeConfiguration.class);
    }

    /** An unreadable row left behind would fail every later resubmission on the shared database. */
    @AfterEach
    void stop() {
        unreadable.forEach(id -> jdbc().update("DELETE FROM event_publication WHERE id = ?", id));
        SpikeListener.RELEASE.release(10);
        instances.close();
    }

    /** Two readable publications whose listener failed, and one whose stored event cannot be read. */
    record Setup(UUID first, UUID second, UUID broken, UUID brokenRow) {
    }

    /** How the stored event is made unreadable. */
    enum Damage {
        /** The payload no longer parses as the event: a field of the wrong type. */
        PAYLOAD("UPDATE event_publication SET serialized_event = '{\"id\":\"not-a-uuid\"}' WHERE id = ?"),
        /** The event class is gone, as after a rename that broke rule 7. */
        TYPE("UPDATE event_publication SET event_type = 'spike.modulith.RemovedEvent' WHERE id = ?");

        final String sql;

        Damage(String sql) {
            this.sql = sql;
        }
    }

    Setup failedPublications(Damage damage) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        // The unreadable one first, a second apart: resubmission reads rows in publication order.
        for (UUID id : List.of(broken, first, second)) {
            SpikeListener.BEHAVIOUR.put(id, Behaviour.FAIL);
            context.getBean(TransactionTemplate.class).executeWithoutResult(
                    tx -> context.publishEvent(new SpikeEvent(id)));
            context.getBean(TestClock.class).advance(Duration.ofSeconds(1));
        }
        awaitStatus(first, "FAILED");
        awaitStatus(second, "FAILED");
        awaitStatus(broken, "FAILED");
        for (UUID id : List.of(first, second, broken)) {
            SpikeListener.BEHAVIOUR.put(id, Behaviour.SUCCEED);
        }
        UUID brokenRow = jdbc().queryForObject(
                "SELECT id FROM event_publication WHERE serialized_event LIKE ?", UUID.class, "%" + broken + "%");
        jdbc().update(damage.sql, brokenRow);
        unreadable.add(brokenRow);
        context.getBean(TestClock.class).advance(Duration.ofMinutes(10));
        return new Setup(first, second, broken, brokenRow);
    }

    JdbcTemplate jdbc() {
        return context.getBean(JdbcTemplate.class);
    }

    IncompleteEventPublications incomplete() {
        return context.getBean(IncompleteEventPublications.class);
    }

    String status(UUID eventId) {
        return jdbc().queryForObject("SELECT status FROM event_publication WHERE serialized_event LIKE ?",
                String.class, "%" + eventId + "%");
    }

    String statusOfRow(UUID row) {
        return jdbc().queryForObject("SELECT status FROM event_publication WHERE id = ?", String.class, row);
    }

    void awaitStatus(UUID eventId, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!expected.equals(status(eventId)) && System.nanoTime() < deadline) {
            pause(50);
        }
        assertThat(status(eventId)).isEqualTo(expected);
    }

    /** Lets asynchronous listeners started by a resubmission finish. */
    static void settle() {
        pause(2000);
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs one resubmission call; returns what it threw, or null. */
    static Throwable attempt(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    void evidence(String experiment, Setup setup, Throwable thrown) {
        System.out.printf("SPIKE-S1 %s | thrown=%s | first=%s (%d calls) | second=%s (%d calls) | broken=%s (%d calls)%n",
                experiment, thrown == null ? "nothing" : thrown.getClass().getSimpleName() + ": " + thrown.getMessage(),
                status(setup.first()), SpikeListener.invocations(setup.first()),
                status(setup.second()), SpikeListener.invocations(setup.second()),
                statusOfRow(setup.brokenRow()), SpikeListener.invocations(setup.broken()));
    }

    /** S1a, today's job: one unreadable row fails the whole run (first-run finding 1, reproduced). */
    @Test
    void s1aOlderThanFailsOnOneUnreadableRow() {
        Setup setup = failedPublications(Damage.PAYLOAD);
        Throwable thrown = attempt(() -> incomplete().resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1)));
        settle();
        evidence("S1a resubmitIncompletePublicationsOlderThan, bad payload", setup, thrown);
        assertThat(thrown).isInstanceOf(tools.jackson.databind.exc.InvalidFormatException.class);
        // Everything behind the unreadable row waits; the row itself is left marked RESUBMITTED.
        assertThat(status(setup.first())).isEqualTo("FAILED");
        assertThat(status(setup.second())).isEqualTo("FAILED");
        assertThat(statusOfRow(setup.brokenRow())).isEqualTo("RESUBMITTED");
    }

    /** S1b: a predicate that selects only readable rows by ID; does reading happen before the predicate? */
    @Test
    void s1bPredicateSelectingOtherRows() {
        Setup setup = failedPublications(Damage.PAYLOAD);
        Throwable thrown = attempt(() -> incomplete().resubmitIncompletePublications(
                p -> !p.getIdentifier().equals(setup.brokenRow())));
        settle();
        evidence("S1b resubmitIncompletePublications(predicate excluding the broken row by ID), bad payload", setup, thrown);
        // The predicate runs before the event is read: rows are deserialized lazily.
        assertThat(thrown).isNull();
        assertReadableCompletedAndBrokenUntouched(setup);
    }

    /** S1c: ResubmissionOptions with a filter and batches of one. */
    @Test
    void s1cOptionsWithFilterAndBatchOfOne() {
        Setup setup = failedPublications(Damage.PAYLOAD);
        Throwable thrown = attempt(() -> incomplete().resubmitIncompletePublications(ResubmissionOptions.defaults()
                .withBatchSize(1)
                .withMinAge(Duration.ofMinutes(1))
                .withFilter(p -> !p.getIdentifier().equals(setup.brokenRow()))));
        settle();
        evidence("S1c ResubmissionOptions(batch 1, filter excluding the broken row), bad payload", setup, thrown);
        // The batch is read before the filter runs: the excluded row at the head fills the batch of one, so
        // nothing is resubmitted, on this call or any later one. With the unreadable row elsewhere, one
        // readable publication per call goes through (first run of this spike). This API also skips
        // publications a crash left in progress (Spike A, A2b), so it is not an option.
        assertThat(thrown).isNull();
        assertThat(List.of(status(setup.first()), status(setup.second()))).containsOnly("FAILED");
        assertThat(SpikeListener.invocations(setup.first())).isEqualTo(1);
    }

    /** S1d: a predicate that reads each event inside try/catch and skips the unreadable one. */
    @Test
    void s1dPredicateCatchingPerRow() {
        Setup setup = failedPublications(Damage.PAYLOAD);
        List<UUID> skipped = new ArrayList<>();
        Throwable thrown = attempt(() -> incomplete().resubmitIncompletePublications(readable(skipped::add)));
        settle();
        evidence("S1d resubmitIncompletePublications(predicate that reads the event in try/catch), bad payload "
                + "| skipped=" + skipped, setup, thrown);
        assertThat(thrown).isNull();
        assertThat(skipped).containsExactly(setup.brokenRow());
        assertReadableCompletedAndBrokenUntouched(setup);
    }

    /** S1e: one call per publication, each in its own try/catch. */
    @Test
    void s1eOneCallPerPublication() {
        Setup setup = failedPublications(Damage.PAYLOAD);
        List<String> outcomes = oneAtATime();
        settle();
        evidence("S1e one resubmitIncompletePublications(identifier equals) call per row, bad payload "
                + "| per-row outcomes=" + outcomes, setup, null);
        assertThat(outcomes).containsExactly(setup.brokenRow() + "=InvalidFormatException");
        assertThat(status(setup.first())).isEqualTo("COMPLETED");
        assertThat(status(setup.second())).isEqualTo("COMPLETED");
        assertThat(statusOfRow(setup.brokenRow())).isEqualTo("RESUBMITTED");
    }

    /** S1f: as S1d and S1e, with the event class missing instead of a bad payload. */
    @Test
    void s1fMissingEventClass() {
        Setup setup = failedPublications(Damage.TYPE);
        List<UUID> skipped = new ArrayList<>();
        Throwable thrown = attempt(() -> incomplete().resubmitIncompletePublications(readable(skipped::add)));
        settle();
        evidence("S1f resubmitIncompletePublications(predicate that reads the event in try/catch), missing class "
                + "| skipped=" + skipped, setup, thrown);
        // The repository drops a row of unknown type when loading (WARN "Event ... of unknown type ...
        // found"): it never reaches the predicate, is never resubmitted, and stays incomplete.
        assertThat(thrown).isNull();
        assertThat(skipped).isEmpty();
        assertReadableCompletedAndBrokenUntouched(setup);
    }

    static java.util.function.Predicate<EventPublication> readable(Consumer<UUID> skipped) {
        return publication -> {
            try {
                publication.getEvent();
                return true;
            } catch (RuntimeException e) {
                System.out.printf("SPIKE-S1 predicate | %s unreadable: %s%n",
                        publication.getIdentifier(), e.getClass().getSimpleName());
                skipped.accept(publication.getIdentifier());
                return false;
            }
        };
    }

    /** One resubmission call per incomplete row found by SQL, each isolated. */
    List<String> oneAtATime() {
        List<UUID> rows = jdbc().queryForList(
                "SELECT id FROM event_publication WHERE completion_date IS NULL ORDER BY publication_date", UUID.class);
        List<String> outcomes = new ArrayList<>();
        for (UUID row : rows) {
            Throwable thrown = attempt(() -> incomplete().resubmitIncompletePublications(
                    p -> p.getIdentifier().equals(row)));
            if (unreadable.contains(row) || thrown != null) {
                outcomes.add(row + "=" + (thrown == null ? "ok" : thrown.getClass().getSimpleName()));
            }
        }
        return outcomes;
    }

    void assertReadableCompletedAndBrokenUntouched(Setup setup) {
        assertThat(status(setup.first())).isEqualTo("COMPLETED");
        assertThat(status(setup.second())).isEqualTo("COMPLETED");
        assertThat(SpikeListener.invocations(setup.first())).isEqualTo(2);
        assertThat(statusOfRow(setup.brokenRow())).isEqualTo("FAILED");
        assertThat(SpikeListener.invocations(setup.broken())).isEqualTo(1);
    }

    /**
     * S1g: the predicate API also resubmits a publication a crash left in progress, as
     * resubmitIncompletePublicationsOlderThan does and ResubmissionOptions does not (Spike A, A2b). It has
     * no minimum age of its own, so the predicate must check the age.
     */
    @Test
    void s1gPredicateCoversCrashLeftPublications() {
        UUID crashed = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(crashed, Behaviour.BLOCK);
        context.getBean(TransactionTemplate.class).executeWithoutResult(
                tx -> context.publishEvent(new SpikeEvent(crashed)));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (SpikeListener.invocations(crashed) == 0 && System.nanoTime() < deadline) {
            pause(50);
        }
        instances.stop(context);
        SpikeListener.BEHAVIOUR.put(crashed, Behaviour.SUCCEED);
        context = instances.start("s1g-restarted", SpikeConfiguration.class);
        assertThat(status(crashed)).isEqualTo("PROCESSING");

        TestClock clock = context.getBean(TestClock.class);
        java.util.function.Predicate<EventPublication> olderThanOneMinute =
                p -> p.getPublicationDate().isBefore(clock.instant().minus(Duration.ofMinutes(1)));
        incomplete().resubmitIncompletePublications(olderThanOneMinute.and(readable(id -> { })));
        settle();
        System.out.printf("SPIKE-S1 S1g predicate (age and readable), crash-left, clock not moved | %s (%d calls)%n",
                status(crashed), SpikeListener.invocations(crashed));
        assertThat(status(crashed)).isEqualTo("PROCESSING");

        clock.advance(Duration.ofMinutes(5));
        incomplete().resubmitIncompletePublications(olderThanOneMinute.and(readable(id -> { })));
        settle();
        System.out.printf("SPIKE-S1 S1g predicate (age and readable), crash-left, clock +5 min | %s (%d calls)%n",
                status(crashed), SpikeListener.invocations(crashed));
        assertThat(status(crashed)).isEqualTo("COMPLETED");
        assertThat(SpikeListener.invocations(crashed)).isEqualTo(2);
    }
}
