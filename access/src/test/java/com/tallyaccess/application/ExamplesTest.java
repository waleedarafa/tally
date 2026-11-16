package com.tallyaccess.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tallyaccess.application.AccessAnswer.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access's consumer contract test (ADR-034, brief PR-6): it reads every file in
 * {@code contracts/access-facts/v1/examples/}, the unknown field and values included, and answers from it.
 */
class ExamplesTest {

    static final Path EXAMPLES = Path.of("../contracts/access-facts/v1/examples");
    static final JsonMapper JSON = JsonMapper.builder().build();

    static AccessFactsMessage read(String file) throws IOException {
        return JSON.readValue(Files.readString(EXAMPLES.resolve(file)), AccessFactsMessage.class);
    }

    static AccessAnswer answerFor(AccessFactsMessage message) {
        return AccessRules.answer(message.accountId(), Optional.of(new StoredFacts(message.accountId(),
                message.sequence(), message.producedAt(), message.standing(), message.sourcesOrNone())));
    }

    @Test
    void accessReadsEveryExampleAndCanStoreIt() throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(EXAMPLES)) {
            files = listed.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(5);
        for (Path file : files) {
            AccessFactsMessage message = read(file.getFileName().toString());
            assertThat(message.complete()).as(file.toString()).isTrue();
        }
    }

    @Test
    void theAnswersOfTheExamples() throws IOException {
        assertThat(answerFor(read("normal.json")).reason()).isEqualTo(Reason.ACTIVE_SOURCE);
        assertThat(answerFor(read("blocked.json")).reason()).isEqualTo(Reason.ACCOUNT_BLOCKED);
        assertThat(answerFor(read("no-source.json")).reason()).isEqualTo(Reason.NO_ACTIVE_SOURCE);
    }

    /**
     * Slice 3: the subscription ENDED (here its first payment was declined, so the standing is GOOD). It leaves the
     * sources, with a higher sequence, and Access says no.
     */
    @Test
    void anEndedSubscriptionLeavesNoSource() throws IOException {
        AccessFactsMessage ended = read("subscription-ended.json");

        assertThat(ended.sourcesOrNone()).isEmpty();
        assertThat(answerFor(ended)).satisfies(answer -> {
            assertThat(answer.canWatch()).isFalse();
            assertThat(answer.reason()).isEqualTo(Reason.NO_ACTIVE_SOURCE);
            assertThat(answer.sequence()).isEqualTo(2L);
        });
    }

    /** A later version's field and values: read without failing, and never taking access away (ADR-033). */
    @Test
    void theFutureExampleIsReadAndItsUnknownValuesDoNotTakeAccessAway() throws IOException {
        AccessFactsMessage future = read("future-unknown-values.json");

        assertThat(future.standing()).isEqualTo("SUSPENDED");
        assertThat(future.sourcesOrNone()).extracting(AccessFactsMessage.Source::kind)
                .containsExactly("SUBSCRIPTION", "PARTNER_BUNDLE");
        assertThat(answerFor(future)).satisfies(answer -> {
            assertThat(answer.canWatch()).isTrue();
            assertThat(answer.reason()).isEqualTo(Reason.ACTIVE_SOURCE);
            assertThat(answer.sequence()).isEqualTo(12L);
        });
    }
}
