package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;

/**
 * The approved dependency graph is pinned in {@code architecture/module-dependencies.txt} (FR-040).
 * During Slice 1R that file holds the target graph of the second design (design.md §3), and this test
 * checks {@code module-dependencies-current.txt}, which each PR moves towards it. PR-10 points this
 * test back at the target file and deletes the current one.
 * FF-1 checks that the code keeps to each module's declared dependencies; this test checks that
 * the declarations themselves are the approved ones, so widening a module's
 * {@code allowedDependencies} fails the build until the pinned graph changes too.
 */
class PinnedModuleDependenciesTest {

    /** Until Slice 1R PR-10: the current graph. Then {@code /architecture/module-dependencies.txt}. */
    static final String GRAPH = "/architecture/module-dependencies-current.txt";

    @Test
    void declaredDependenciesMatchTheApprovedGraph() throws IOException {
        Map<String, Set<String>> declared = new TreeMap<>();
        ModuleStructureTest.MODULES.forEach(module -> declared.put(module.getIdentifier().toString(),
                allowedDependencies(module.getBasePackage().getName())));

        assertThat(declared)
                .as("Declared module dependencies (package-info.java) differ from the approved graph in "
                        + GRAPH + ". Changing them needs approval (CLAUDE.md)")
                .isEqualTo(approvedGraph());
    }

    private static Set<String> allowedDependencies(String basePackage) {
        try {
            ApplicationModule annotation = Class.forName(basePackage + ".package-info")
                    .getAnnotation(ApplicationModule.class);
            assertThat(annotation).as("@ApplicationModule on %s", basePackage).isNotNull();
            return new TreeSet<>(Arrays.asList(annotation.allowedDependencies()));
        } catch (ClassNotFoundException e) {
            throw new AssertionError("No package-info.java in " + basePackage, e);
        }
    }

    private static Map<String, Set<String>> approvedGraph() throws IOException {
        try (InputStream in = PinnedModuleDependenciesTest.class
                .getResourceAsStream(GRAPH)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toMap(
                            line -> line.substring(0, line.indexOf(':')).strip(),
                            line -> Arrays.stream(line.substring(line.indexOf(':') + 1).split(","))
                                    .map(String::strip)
                                    .filter(s -> !s.isEmpty())
                                    .collect(Collectors.toCollection(TreeSet::new)),
                            (a, b) -> {
                                throw new IllegalStateException("Module listed twice");
                            },
                            TreeMap::new));
        }
    }
}
