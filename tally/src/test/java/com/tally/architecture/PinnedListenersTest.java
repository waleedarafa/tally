package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

/**
 * CLAUDE.md rule 7 (Slice 1R PR-10): a listener is pinned like an event class. Spring Modulith stores each
 * pending publication with its listener's identity, {@code class.method(parameter types)}, so renaming or
 * moving a listener orphans its pending publications. Every event listener (any method annotated, directly or
 * through {@code @ApplicationModuleListener} or {@code @TransactionalEventListener}, with {@link EventListener})
 * must appear in {@code architecture/pinned-listeners.txt}, and every pinned identity must still exist.
 */
class PinnedListenersTest {

    /** The identity Spring Modulith stores with a publication, as {@code UnknownEnumValuesIT} uses it. */
    static String identity(JavaMethod method) {
        return method.getOwner().getName() + "." + method.getName() + method.getRawParameterTypes().stream()
                .map(JavaClass::getName).collect(Collectors.joining(",", "(", ")"));
    }

    @Test
    void eventListenersMatchThePinnedList() throws IOException {
        Set<String> actual = InsideLayout.CLASSES.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(EventListener.class) || m.isMetaAnnotatedWith(EventListener.class))
                .map(PinnedListenersTest::identity)
                .collect(Collectors.toSet());
        Set<String> pinned = pinnedListeners();

        assertThat(actual).as("event listeners found").isNotEmpty();
        assertThat(pinned)
                .as("Pinned listeners that no longer exist. A listener is never renamed or moved while it may have"
                        + " incomplete publications: deploy the new one first, then drain the old (rule 7)")
                .isSubsetOf(actual);
        assertThat(actual)
                .as("Event listeners missing from architecture/pinned-listeners.txt. Pin every new listener (rule 7)")
                .isSubsetOf(pinned);
    }

    private static Set<String> pinnedListeners() throws IOException {
        try (InputStream in = PinnedListenersTest.class.getResourceAsStream("/architecture/pinned-listeners.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        }
    }
}
