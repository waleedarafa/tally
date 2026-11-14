package com.tallyaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * F7, Access side (ADR-001, ADR-034; CLAUDE.md rule 1): Access is a second deployable and never depends on billing
 * code. Its sources import no {@code com.tally} class, and its build does not depend on the {@code tally} module. It
 * shares with billing only the files in {@code contracts/access-facts/}.
 */
class NoBillingCodeTest {

    /** A name in billing's packages, {@code com.tally.<module>}; Access's own is {@code com.tallyaccess}. */
    static final Pattern BILLING = Pattern.compile("\\bcom\\.tally\\.[a-z]");

    @Test
    void noSourceOfAccessUsesBillingCode() throws IOException {
        List<String> offending;
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            offending = files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return BILLING.matcher(Files.readString(f)).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(Path::toString).toList();
        }
        assertThat(offending).as("Access imports billing code (F7)").isEmpty();
    }

    @Test
    void accessesBuildDoesNotDependOnTheTallyModule() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).doesNotContain("<artifactId>tally</artifactId>")
                .doesNotContain("<artifactId>fake-provider</artifactId>");
    }
}
