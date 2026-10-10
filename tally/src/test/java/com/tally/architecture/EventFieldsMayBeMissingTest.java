package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.shared.DomainEvent;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * FF-13, CLAUDE.md rule 7 (add-only events; Slice 1R PR-7 finding): Jackson 3 refuses a stored event
 * that lacks a primitive field ({@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by default), so adding a
 * primitive field to an event breaks every publication stored before it. Every event field is therefore a
 * reference type, null when absent, except the primitives events have carried since they were first
 * stored, pinned in {@link #SINCE_FIRST_STORED}. {@code EventEnvelopeIT} shows the failure and the fix.
 */
class EventFieldsMayBeMissingTest {

    /** Primitive fields present since the event was first stored: no stored payload lacks them. */
    static final Set<String> SINCE_FIRST_STORED = Set.of("com.tally.subscription.SubscriptionChanged.version");

    @Test
    void everyEventFieldAddedLaterIsAReferenceType() {
        Set<String> primitives = new TreeSet<>();
        new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.tally").stream()
                .filter(c -> c.isAssignableTo(DomainEvent.class) && !c.isInterface())
                .map(c -> c.reflect())
                .forEach(type -> {
                    assertThat(type.isRecord()).as(type.getName() + " is a record").isTrue();
                    Arrays.stream(type.getRecordComponents())
                            .filter(component -> component.getType().isPrimitive())
                            .forEach(component -> primitives.add(type.getName() + "." + component.getName()));
                });

        assertThat(primitives)
                .as("Primitive event fields: a stored event without one cannot be read (rule 7, Jackson 3)")
                .isSubsetOf(SINCE_FIRST_STORED);
    }
}
