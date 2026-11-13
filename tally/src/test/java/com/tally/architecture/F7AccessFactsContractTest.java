package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.accessfeed.application.AccessFactsMessages;
import com.tally.accessfeed.domain.AccountFacts;
import com.tally.accessfeed.domain.SourceKind;
import com.tally.accessfeed.domain.SourceState;
import com.tally.account.Standing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F7, billing side (ADR-011, ADR-034): every message {@code accessfeed} can build is valid against
 * {@code contracts/access-facts/v1/schema.json} and carries every field Access reads
 * ({@code consumer-access.json}). A change that breaks a field Access reads fails billing's commit stage.
 *
 * <p>"Every message it can build": every standing the facts accept, each with no source, one source, and two
 * sources, for every source kind and state. A standing the facts refuse ({@code UNKNOWN}) is never built.
 */
class F7AccessFactsContractTest {

    static final Path CONTRACT = Path.of("../contracts/access-facts/v1");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-11-13T06:00:00Z");

    static JsonNode read(Path file) throws IOException {
        return JSON.readTree(Files.readString(file));
    }

    /** Every message the feed can build, as JSON. */
    static List<String> everyMessage() {
        List<String> messages = new ArrayList<>();
        for (Standing standing : Standing.values()) {
            for (int sources = 0; sources <= 2; sources++) {
                for (SourceKind kind : SourceKind.values()) {
                    for (SourceState state : SourceState.values()) {
                        AccountFacts facts = AccountFacts.none(UUID.randomUUID());
                        try {
                            facts.applyStanding(standing, 1L, NOW);
                        } catch (IllegalArgumentException refused) {
                            continue;
                        }
                        for (int i = 0; i < sources; i++) {
                            facts.applySource(kind, UUID.randomUUID(), state, 1L, NOW);
                        }
                        if (facts.sequence() > 0) {
                            messages.add(AccessFactsMessages.json(facts));
                        }
                    }
                }
            }
        }
        return messages;
    }

    @Test
    void everyMessageTheFeedCanBuildIsValidVersion1() throws IOException {
        JsonNode schema = read(CONTRACT.resolve("schema.json"));
        List<String> messages = everyMessage();
        assertThat(messages).as("the standings and sources the feed accepts").hasSizeGreaterThanOrEqualTo(6);

        for (String message : messages) {
            assertThat(JsonSchemaSubset.violations(schema, JSON.readTree(message))).as(message).isEmpty();
        }
    }

    @Test
    void everyMessageTheFeedCanBuildCarriesEveryFieldAccessReads() throws IOException {
        List<String> reads = new ArrayList<>();
        read(CONTRACT.resolve("consumer-access.json")).get("reads").forEach(field -> reads.add(field.asString()));
        assertThat(reads).contains("accountId", "sequence", "standing");

        for (String message : everyMessage()) {
            JsonNode node = JSON.readTree(message);
            for (String field : reads) {
                assertThat(has(node, field)).as("%s in %s", field, message).isTrue();
            }
        }
    }

    /** {@code a} is a field; {@code a[].b} is field b of every element of array a (true for an empty array). */
    static boolean has(JsonNode message, String field) {
        if (!field.contains("[].")) {
            return message.has(field) && !message.get(field).isNull();
        }
        String array = field.substring(0, field.indexOf("[]."));
        String inner = field.substring(field.indexOf("[].") + 3);
        if (!message.path(array).isArray()) {
            return false;
        }
        for (JsonNode element : message.get(array)) {
            if (!has(element, inner)) {
                return false;
            }
        }
        return true;
    }

    /** The examples Access reads in its consumer test: valid version 1, except the future one, which must not be. */
    @Test
    void theExamplesAreVersion1ExceptTheFutureOne() throws IOException {
        JsonNode schema = read(CONTRACT.resolve("schema.json"));
        List<Path> examples;
        try (Stream<Path> files = Files.list(CONTRACT.resolve("examples"))) {
            examples = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(examples).extracting(p -> p.getFileName().toString())
                .contains("normal.json", "blocked.json", "future-unknown-values.json");

        for (Path example : examples) {
            List<String> violations = JsonSchemaSubset.violations(schema, read(example));
            if (example.getFileName().toString().startsWith("future-")) {
                assertThat(violations).as("%s holds values version 1 does not know", example).isNotEmpty();
            } else {
                assertThat(violations).as(example.toString()).isEmpty();
            }
        }
    }
}
