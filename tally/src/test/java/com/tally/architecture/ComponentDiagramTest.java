package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.docs.Documenter;

/**
 * C4 level 3 for the billing application, generated from the code by Spring Modulith (topic tracker 0-07). The
 * diagram is committed at {@code docs/architecture/v2/components.puml} so it can be read without a build; this
 * test fails when the committed copy no longer matches the code, so it cannot drift. The modules and their
 * dependencies are pinned (FR-040), so it changes only when an approved change to the graph does.
 *
 * <p>The Documenter does not write components and relations in a stable order: two builds of the same code gave
 * the same lines in a different order. So both sides are compared, and the committed copy is written, with each
 * run of component lines and each run of relation lines sorted. The order of these lines carries no meaning.
 *
 * <p>To refresh the committed copy after an approved change: {@code ./mvnw verify -Dtally.docs.update=true}, then
 * commit the file.
 */
class ComponentDiagramTest {

    static final Path COMMITTED = Path.of("../docs/architecture/v2/components.puml");
    static final Path GENERATED = Path.of("target/modulith-docs");

    @Test
    void theCommittedComponentDiagramMatchesTheCode() throws IOException {
        new Documenter(ModuleStructureTest.MODULES, Documenter.Options.defaults().withOutputFolder(GENERATED.toString()))
                .writeModulesAsPlantUml();
        String generated = stableOrder(Files.readString(GENERATED.resolve("components.puml")));

        if (Boolean.getBoolean("tally.docs.update")) {
            Files.writeString(COMMITTED, generated);
        }

        assertThat(COMMITTED).as("docs/architecture/v2/components.puml is missing; run ./mvnw verify"
                + " -Dtally.docs.update=true and commit it").exists();
        assertThat(stableOrder(Files.readString(COMMITTED)))
                .as("docs/architecture/v2/components.puml no longer matches the code. If the module graph change is"
                        + " approved, run ./mvnw verify -Dtally.docs.update=true and commit the file")
                .isEqualTo(generated);
    }

    /** Sorts each run of consecutive lines of the same kind (component or relation); other lines keep their place. */
    static String stableOrder(String plantUml) {
        List<String> out = new ArrayList<>();
        List<String> run = new ArrayList<>();
        String runKind = null;
        for (String line : plantUml.split("\n", -1)) {
            String kind = kind(line);
            if (kind == null || !kind.equals(runKind)) {
                run.sort(null);
                out.addAll(run);
                run.clear();
            }
            runKind = kind;
            if (kind == null) {
                out.add(line);
            } else {
                run.add(line);
            }
        }
        run.sort(null);
        out.addAll(run);
        return String.join("\n", out);
    }

    private static String kind(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("Component(")) {
            return "component";
        }
        if (trimmed.startsWith("Rel(")) {
            return "relation";
        }
        return null;
    }
}
