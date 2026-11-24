package com.tally.flags.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlagTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final String NAME = "family-bundle";

    @Test
    void aNewlyDefinedFlagIsOffByDefault() {
        Flags flags = new Flags();

        flags.define(NAME, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "US")).isFalse();
    }

    @Test
    void countriesAreAcceptedHoweverTheyAreWritten() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.assignCountry(NAME, "US", true, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "us")).isTrue();
        assertThat(flags.isOn(NAME, ACCOUNT_A, " US ")).isTrue();
    }

    @Test
    void aCountryAssignmentAppliesToEveryAccountInTheCountry() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        flags.assignCountry(NAME, "ae", true, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "AE")).isTrue();
        assertThat(flags.isOn(NAME, ACCOUNT_A, "US")).isFalse();
    }

    @Test
    void anAccountAssignmentOverridesACountryAssignment() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.assignCountry(NAME, "AE", true, NOW);

        flags.assignAccount(NAME, ACCOUNT_A, false, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "AE")).isFalse();
        assertThat(flags.isOn(NAME, ACCOUNT_B, "AE")).isTrue();
    }

    @Test
    void anAccountAssignmentOverridesTheDefault() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        flags.assignAccount(NAME, ACCOUNT_A, true, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "US")).isTrue();
    }

    @Test
    void clearingAnAccountAssignmentFallsBackToTheCountryAssignment() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.assignCountry(NAME, "AE", true, NOW);
        flags.assignAccount(NAME, ACCOUNT_A, false, NOW);

        flags.clearAccountAssignment(NAME, ACCOUNT_A, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "AE")).isTrue();
    }

    @Test
    void clearingTheCountryAssignmentTooFallsBackToTheDefault() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.assignCountry(NAME, "AE", true, NOW);
        flags.assignAccount(NAME, ACCOUNT_A, true, NOW);

        flags.clearAccountAssignment(NAME, ACCOUNT_A, NOW);
        flags.clearCountryAssignment(NAME, "AE", NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "AE")).isFalse();
    }

    @Test
    void aDefaultOnFlagIsOnForEveryoneWithoutAnOffAssignment() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        flags.setDefault(NAME, true, NOW);

        assertThat(flags.isOn(NAME, ACCOUNT_A, "US")).isTrue();
        assertThat(flags.isOn(NAME, ACCOUNT_B, "AE")).isTrue();

        flags.assignCountry(NAME, "AE", false, NOW);
        assertThat(flags.isOn(NAME, ACCOUNT_B, "AE")).isFalse();

        flags.assignAccount(NAME, ACCOUNT_A, false, NOW);
        assertThat(flags.isOn(NAME, ACCOUNT_A, "US")).isFalse();
    }

    @Test
    void theChangeLogIsRecordedInOrder() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.setDefault(NAME, true, NOW.plusSeconds(1));
        flags.assignCountry(NAME, "AE", true, NOW.plusSeconds(2));
        flags.assignAccount(NAME, ACCOUNT_A, false, NOW.plusSeconds(3));
        flags.clearAccountAssignment(NAME, ACCOUNT_A, NOW.plusSeconds(4));
        flags.release(NAME, NOW.plusSeconds(5));

        assertThat(flags.changes(NAME)).containsExactly(
                new FlagChange(NOW, "defined, default off"),
                new FlagChange(NOW.plusSeconds(1), "default set to on"),
                new FlagChange(NOW.plusSeconds(2), "country AE set to on"),
                new FlagChange(NOW.plusSeconds(3), "account 00000000-0000-0000-0000-00000000000a set to off"),
                new FlagChange(NOW.plusSeconds(4), "account 00000000-0000-0000-0000-00000000000a assignment cleared"),
                new FlagChange(NOW.plusSeconds(5), "fully released"));
    }

    @Test
    void releasingIsRejectedWhileTheDefaultIsOff() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        assertThatThrownBy(() -> flags.release(NAME, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default");
    }

    @Test
    void releasingIsRejectedWhileAnyAssignmentIsOff() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.setDefault(NAME, true, NOW);

        flags.assignAccount(NAME, ACCOUNT_A, false, NOW);
        assertThatThrownBy(() -> flags.release(NAME, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        flags.assignAccount(NAME, ACCOUNT_A, true, NOW);
        flags.assignCountry(NAME, "AE", false, NOW);
        assertThatThrownBy(() -> flags.release(NAME, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFullyReleasedFlagIsOverdueMoreThanThirtyDaysAfterRelease() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.setDefault(NAME, true, NOW);
        flags.release(NAME, NOW);

        assertThat(flags.isOverdue(NAME, NOW)).isFalse();
        assertThat(flags.isOverdue(NAME, NOW.plus(30, ChronoUnit.DAYS))).isFalse();
        assertThat(flags.isOverdue(NAME, NOW.plus(31, ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void afterReleaseOnlyRemoveIsAllowed() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.setDefault(NAME, true, NOW);
        flags.release(NAME, NOW);

        assertThatThrownBy(() -> flags.setDefault(NAME, false, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.assignAccount(NAME, ACCOUNT_A, true, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.assignCountry(NAME, "AE", true, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.clearAccountAssignment(NAME, ACCOUNT_A, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.clearCountryAssignment(NAME, "AE", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.release(NAME, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        flags.remove(NAME, NOW);

        assertThatThrownBy(() -> flags.isOn(NAME, ACCOUNT_A, "US"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown flag");
        assertThatThrownBy(() -> flags.isOverdue(NAME, NOW.plus(31, ChronoUnit.DAYS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown flag");

        flags.define(NAME, NOW);

        assertThat(flags.isOverdue(NAME, NOW.plus(31, ChronoUnit.DAYS))).isFalse();
    }

    @Test
    void anUnreleasedFlagIsNeverOverdue() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        assertThat(flags.isOverdue(NAME, NOW.plus(31, ChronoUnit.DAYS))).isFalse();
    }

    @Test
    void clearingAnAssignmentThatDoesNotExistIsANoOp() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        flags.clearAccountAssignment(NAME, ACCOUNT_A, NOW);
        flags.clearCountryAssignment(NAME, "AE", NOW);

        assertThat(flags.changes(NAME)).containsExactly(new FlagChange(NOW, "defined, default off"));
    }

    @Test
    void invalidNamesAreRejected() {
        Flags flags = new Flags();

        for (String bad : new String[]{null, "", "  ", "Family", "1bundle", "family_bundle"}) {
            assertThatThrownBy(() -> flags.define(bad, NOW)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void invalidCountriesAreRejected() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        for (String bad : new String[]{"USA", "A", "A1", null, ""}) {
            assertThatThrownBy(() -> flags.isOn(NAME, ACCOUNT_A, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void accountIdIsRequired() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        assertThatThrownBy(() -> flags.isOn(NAME, null, "US")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.assignAccount(NAME, null, true, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flags.clearAccountAssignment(NAME, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
