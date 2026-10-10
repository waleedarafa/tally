package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * F4 (ADR-017, R5): the shared kernel holds only {@code Money}, {@code Ratio} and the event marker
 * {@code DomainEvent}. The list is pinned: adding a class to {@code shared} fails here and needs a
 * decision, because every module depends on {@code shared}.
 */
class SharedKernelTest {

    @Test
    void sharedHoldsOnlyMoneyRatioAndTheEventMarker() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.tally.shared");

        assertThat(classes.stream()
                .filter(c -> c.getPackageName().startsWith("com.tally.shared"))
                .filter(c -> !c.getSimpleName().equals("package-info"))
                .map(JavaClass::getName))
                .containsExactlyInAnyOrder(
                        "com.tally.shared.DomainEvent",
                        "com.tally.shared.Money",
                        "com.tally.shared.Ratio");
    }
}
