package com.tally.flags.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.tally.flags.FlagChangeView;
import com.tally.flags.FlagConflictException;
import com.tally.flags.FlagNotFoundException;
import com.tally.flags.FlagReport;
import com.tally.flags.FlagView;
import com.tally.flags.InvalidFlagRequestException;
import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.Flags;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlagsAdminServiceTest {

    static final String STAFF = "alice";
    static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    static final class InMemoryCatalogue implements FlagCatalogue {
        Flags flags = new Flags();
        int loadCalls;
        int loadForUpdateCalls;
        int saveCalls;

        @Override
        public Flags load() {
            loadCalls++;
            return flags;
        }

        @Override
        public Flags loadForUpdate() {
            loadForUpdateCalls++;
            return flags;
        }

        @Override
        public void save(Flags snapshot) {
            saveCalls++;
            flags = snapshot;
        }
    }

    InMemoryCatalogue catalogue;
    FlagsAdminService service;

    @BeforeEach
    void setUp() {
        catalogue = new InMemoryCatalogue();
        service = new FlagsAdminService(catalogue, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void definingAFlagReturnsTheViewAndSaves() {
        FlagView view = service.define("family-bundle", STAFF);

        assertThat(view.name()).isEqualTo("family-bundle");
        assertThat(view.defaultOn()).isFalse();
        assertThat(view.releasedAt()).isNull();
        assertThat(view.removeBy()).isNull();
        assertThat(view.overdue()).isFalse();
        assertThat(view.changeCount()).isEqualTo(1);
        assertThat(view.lastChangeAt()).isEqualTo(NOW);
        assertThat(view.changes()).containsExactly(new FlagChangeView(NOW, "defined, default off"));
        assertThat(catalogue.loadForUpdateCalls).isEqualTo(1);
        assertThat(catalogue.saveCalls).isEqualTo(1);
    }

    @Test
    void definingAgainIsAConflictAndBadNamesAreRejectedBeforeLoading() {
        service.define("family-bundle", STAFF);

        assertThatThrownBy(() -> service.define("family-bundle", STAFF))
                .isInstanceOf(FlagConflictException.class);

        for (String bad : new String[]{"Family Bundle", "1x"}) {
            assertThatThrownBy(() -> service.define(bad, STAFF))
                    .isInstanceOf(InvalidFlagRequestException.class);
        }

        assertThat(catalogue.loadForUpdateCalls).isEqualTo(2);
        assertThat(catalogue.saveCalls).isEqualTo(1);
    }

    @Test
    void unknownNamesAreNotFoundForEveryMethod() {
        assertThatThrownBy(() -> service.flag("nope"))
                .isInstanceOf(FlagNotFoundException.class);
        assertThatThrownBy(() -> service.setDefault("nope", true, STAFF))
                .isInstanceOf(FlagNotFoundException.class);
        assertThatThrownBy(() -> service.release("nope", STAFF))
                .isInstanceOf(FlagNotFoundException.class);
        assertThatThrownBy(() -> service.remove("nope", STAFF))
                .isInstanceOf(FlagNotFoundException.class);
    }

    @Test
    void defaultAssignmentCountryAndClearStayConsistent() {
        service.define("family-bundle", STAFF);

        FlagView defaulted = service.setDefault("family-bundle", true, STAFF);
        assertThat(defaulted.defaultOn()).isTrue();

        service.assignAccount("family-bundle", ACCOUNT_A, false, STAFF);
        FlagView assigned = service.assignCountry("family-bundle", "ae", true, STAFF);
        assertThat(assigned.accounts()).containsExactly(entry(ACCOUNT_A, false));
        assertThat(assigned.countries()).containsExactly(entry("AE", true));
        assertThat(assigned.changeCount()).isEqualTo(4);

        FlagView cleared = service.clearAccount("family-bundle", ACCOUNT_A, STAFF);
        assertThat(cleared.accounts()).isEmpty();
        assertThat(cleared.changeCount()).isEqualTo(5);

        int saves = catalogue.saveCalls;
        FlagView stillCleared = service.clearAccount("family-bundle", ACCOUNT_A, STAFF);
        assertThat(stillCleared.changeCount()).isEqualTo(5);
        assertThat(catalogue.saveCalls).isEqualTo(saves);
    }

    @Test
    void badCountriesAndAMissingAccountIdAreInvalid() {
        service.define("family-bundle", STAFF);

        assertThatThrownBy(() -> service.assignCountry("family-bundle", "ARE", true, STAFF))
                .isInstanceOf(InvalidFlagRequestException.class);
        assertThatThrownBy(() -> service.assignCountry("family-bundle", "1", true, STAFF))
                .isInstanceOf(InvalidFlagRequestException.class);
        assertThatThrownBy(() -> service.assignAccount("family-bundle", null, true, STAFF))
                .isInstanceOf(InvalidFlagRequestException.class);
    }

    @Test
    void releaseRequiresTheDefaultOnAndThenMakesTheFlagImmutable() {
        service.define("family-bundle", STAFF);

        assertThatThrownBy(() -> service.release("family-bundle", STAFF))
                .isInstanceOf(FlagConflictException.class);

        service.setDefault("family-bundle", true, STAFF);
        FlagView released = service.release("family-bundle", STAFF);

        assertThat(released.releasedAt()).isEqualTo(NOW);
        assertThat(released.removeBy()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
        assertThat(released.overdue()).isFalse();

        assertThatThrownBy(() -> service.release("family-bundle", STAFF))
                .isInstanceOf(FlagConflictException.class);
        assertThatThrownBy(() -> service.setDefault("family-bundle", false, STAFF))
                .isInstanceOf(FlagConflictException.class);
    }

    @Test
    void reportListsFlagsSortedAndOnlyTheOverdueOnes() {
        seedCatalogue();

        assertThat(catalogue.loadCalls).isZero();
        FlagReport report = service.report();

        assertThat(report.asOf()).isEqualTo(NOW);
        assertThat(report.flags().stream().map(FlagView::name).toList())
                .containsExactly("edge", "new-one", "old-one", "plain");
        assertThat(report.overdue()).containsExactly("old-one");

        FlagView oldOne = report.flags().stream()
                .filter(view -> view.name().equals("old-one")).findFirst().orElseThrow();
        assertThat(oldOne.overdue()).isTrue();
        assertThat(oldOne.removeBy()).isEqualTo(Instant.parse("2026-10-31T00:00:00Z"));

        FlagView plain = report.flags().stream()
                .filter(view -> view.name().equals("plain")).findFirst().orElseThrow();
        assertThat(plain.changeCount()).isEqualTo(1);

        service.flag("plain");
        assertThat(catalogue.loadForUpdateCalls).isZero();
        assertThat(catalogue.saveCalls).isZero();
    }

    @Test
    void removeDropsTheFlagFromTheReportAndReleasesTheNameAgain() {
        seedCatalogue();

        service.remove("old-one", STAFF);

        FlagReport report = service.report();
        assertThat(report.flags().stream().map(FlagView::name).toList())
                .containsExactly("edge", "new-one", "plain");
        assertThat(report.overdue()).isEmpty();

        FlagView redefined = service.define("old-one", STAFF);
        assertThat(redefined.changeCount()).isEqualTo(1);
    }

    private void seedCatalogue() {
        Flags flags = new Flags();
        flags.define("plain", Instant.parse("2026-11-01T00:00:00Z"));
        flags.define("new-one", Instant.parse("2026-11-01T00:00:00Z"));
        flags.setDefault("new-one", true, Instant.parse("2026-11-01T00:00:00Z"));
        flags.release("new-one", Instant.parse("2026-11-20T00:00:00Z"));
        flags.define("old-one", Instant.parse("2026-10-01T00:00:00Z"));
        flags.setDefault("old-one", true, Instant.parse("2026-10-01T00:00:00Z"));
        flags.release("old-one", Instant.parse("2026-10-01T00:00:00Z"));
        flags.define("edge", Instant.parse("2026-10-20T00:00:00Z"));
        flags.setDefault("edge", true, Instant.parse("2026-10-20T00:00:00Z"));
        flags.release("edge", Instant.parse("2026-10-27T10:00:00Z"));
        catalogue.flags = flags;
    }
}
