package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.support.AccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The rebuild runbook gives the statement that AT-19 runs (Slice 2 PR-8): the facts, the positions and the ADR-036
 * mark, emptied in one statement. A runbook that drifts from the steps a test proves is a runbook nobody has run.
 */
class AccessRebuildRunbookTest {

    @Test
    void theRunbookEmptiesTheDatabaseWithTheStatementAt19Proves() throws IOException {
        String runbook = Files.readString(Path.of("../docs/runbooks/access-rebuild.md"));
        assertThat(runbook).contains(AccessService.EMPTY_DATABASE);
    }
}
