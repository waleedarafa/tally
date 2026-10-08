package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** R6: each instance aims at its share of 95% of the provider's limit. */
class PermitIntervalTest {

    @Test
    void twoInstancesAt95PercentOfOneHundredCallsASecondMakeAbout95Together() {
        Duration interval = ProviderIsolation.permitInterval(100, 2, 95);

        assertThat(interval).isEqualTo(Duration.ofNanos(21_052_631));
        assertThat(2 * 1_000_000_000.0 / interval.toNanos()).isCloseTo(95.0, offset(0.01));
    }

    @Test
    void oneHundredPercentKeepsTheEvenShare() {
        assertThat(ProviderIsolation.permitInterval(100, 2, 100)).isEqualTo(Duration.ofMillis(20));
    }
}
