package com.fakeprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModesTest {

    @Test
    void modesCombine() {
        Modes m = Modes.parse(List.of("SLOW(200)", "LIE", "DECLINE_SOFT(10)", "RATE_LIMIT(100)"));

        assertThat(m.slowMillis()).isEqualTo(200);
        assertThat(m.lie()).isTrue();
        assertThat(m.declineSoftPercent()).isEqualTo(10);
        assertThat(m.rateLimitPerSecond()).isEqualTo(100);
        assertThat(m.outage()).isFalse();
    }

    @Test
    void normalResets() {
        assertThat(Modes.parse(List.of("OUTAGE", "NORMAL"))).isEqualTo(Modes.NORMAL);
        assertThat(Modes.parse(List.of())).isEqualTo(Modes.NORMAL);
    }

    @Test
    void rejectsUnknownOrIncompleteModes() {
        assertThatThrownBy(() -> Modes.parse(List.of("EXPLODE"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Modes.parse(List.of("SLOW"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Modes.parse(List.of("DECLINE_HARD(150)"))).isInstanceOf(IllegalArgumentException.class);
    }
}
