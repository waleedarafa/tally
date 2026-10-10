package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.shared.DomainEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * FR-009: event classes never change name or package. Every implementation of
 * {@link DomainEvent} must appear in {@code architecture/pinned-events.txt}, and every pinned
 * name must still exist. A rename or move therefore fails the build on the missing name.
 */
class PinnedEventClassesTest {

    @Test
    void eventClassesMatchThePinnedList() throws IOException {
        Set<String> actual = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.tally")
                .stream()
                .filter(c -> c.isAssignableTo(DomainEvent.class) && !c.isInterface())
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        Set<String> pinned = pinnedEventClasses();

        assertThat(pinned)
                .as("Pinned event classes that no longer exist. Event classes are never renamed or moved (FR-009)")
                .isSubsetOf(actual);
        assertThat(actual)
                .as("Event classes missing from architecture/pinned-events.txt. Pin every new event (FR-009)")
                .isSubsetOf(pinned);
    }

    private static Set<String> pinnedEventClasses() throws IOException {
        try (InputStream in = PinnedEventClassesTest.class.getResourceAsStream("/architecture/pinned-events.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        }
    }
}
