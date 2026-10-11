package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.tally.shared.DomainEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * FF-12, CLAUDE.md rule 7: consumers must handle enum values they do not know. Every enum that
 * crosses a module boundary (a top-level enum in a module's base package, its public API) or is stored in an
 * event (a component of a {@link DomainEvent}) has one constant marked {@link JsonEnumDefaultValue}, which
 * Jackson maps values this version does not know to. It is {@code UNKNOWN}; where UNKNOWN is a real state,
 * it is {@code UNRECOGNISED} instead (ADR-024, amends ADR-016), so an unrecognised value is never
 * read as that state. The enums with a real UNKNOWN are pinned in {@link #UNKNOWN_IS_A_STATE}.
 */
class EnumsHaveUnknownDefaultTest {

    static final Set<JavaClass> CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.tally")
            .stream().collect(Collectors.toSet());

    /** Enums whose UNKNOWN is a domain value: "the charge may have happened" for a payment attempt. */
    static final Set<String> UNKNOWN_IS_A_STATE = Set.of("com.tally.payment.AttemptState");

    @Test
    void everyCrossModuleOrEventEnumHasAnUnknownDefault() {
        Set<Class<?>> enums = crossModuleOrEventEnums();
        // The list this rule covers, so a new enum shows up in review. Update it when one is added.
        assertThat(enums).extracting(Class::getName).containsExactlyInAnyOrder(
                "com.tally.account.Standing",
                "com.tally.collections.CaseStatus",
                "com.tally.invoicing.InvoiceStatus",
                "com.tally.payment.AttemptState",
                "com.tally.payment.FailureReason",
                "com.tally.subscription.SubscriptionChangeType");

        Set<String> wrong = new TreeSet<>();
        for (Class<?> type : enums) {
            String expected = UNKNOWN_IS_A_STATE.contains(type.getName()) ? "UNRECOGNISED" : "UNKNOWN";
            List<String> defaults = Arrays.stream(type.getFields())
                    .filter(f -> f.isEnumConstant() && f.isAnnotationPresent(JsonEnumDefaultValue.class))
                    .map(java.lang.reflect.Field::getName).toList();
            if (!defaults.equals(List.of(expected))) {
                wrong.add(type.getName() + " has " + defaults + ", needs [" + expected + "]");
            }
        }
        assertThat(wrong)
                .as("Enums whose @JsonEnumDefaultValue is not the one constant UNKNOWN, or UNRECOGNISED where"
                        + " UNKNOWN is a real state (CLAUDE.md rule 7, ADR-024)")
                .isEmpty();
    }

    static Set<Class<?>> crossModuleOrEventEnums() {
        Set<Class<?>> enums = new HashSet<>();
        for (JavaClass c : CLASSES) {
            // Top-level only: a nested enum such as platform's Outcome.Done is a singleton marker, not a value.
            if (c.isEnum() && c.isTopLevelClass() && isInAModuleBasePackage(c)) {
                enums.add(c.reflect());
            }
            if (c.isAssignableTo(DomainEvent.class) && c.isRecord()) {
                for (RecordComponent component : c.reflect().getRecordComponents()) {
                    if (component.getType().isEnum()) {
                        enums.add(component.getType());
                    }
                }
            }
        }
        return enums;
    }

    /** {@code com.tally.<module>}, not a subpackage. */
    static boolean isInAModuleBasePackage(JavaClass c) {
        String pkg = c.getPackageName();
        return pkg.startsWith("com.tally.") && pkg.indexOf('.', "com.tally.".length()) < 0;
    }
}
