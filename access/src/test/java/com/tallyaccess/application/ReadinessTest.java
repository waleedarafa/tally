package com.tallyaccess.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Brief PR-6: not ready, and no answer, until the copy has reached the end of the log seen at start. */
class ReadinessTest {

    final Readiness readiness = new Readiness(event -> { });

    @Test
    void notReadyUntilEveryPartitionHasReachedItsEndAtStart() {
        readiness.endAtStart(Map.of(0, 120L, 1, 40L));
        assertThat(readiness.ready()).isFalse();

        readiness.check(Map.of(0, 120L));
        assertThat(readiness.ready()).as("partition 1 not read yet").isFalse();
        readiness.check(Map.of(0, 120L, 1, 39L));
        assertThat(readiness.ready()).isFalse();

        readiness.check(Map.of(0, 121L, 1, 40L));
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void anEmptyLogIsReadyAtOnce() {
        readiness.endAtStart(Map.of(0, 0L));
        readiness.check(Map.of());
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void notReadyBeforeTheEndIsKnown() {
        readiness.check(Map.of(0, 5L));
        assertThat(readiness.ready()).isFalse();
    }
}
