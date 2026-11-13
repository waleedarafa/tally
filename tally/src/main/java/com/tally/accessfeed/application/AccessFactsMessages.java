package com.tally.accessfeed.application;

import com.tally.accessfeed.domain.AccountFacts;
import com.tally.accessfeed.domain.Source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The message the feed sends to the event log: AccessFacts, version 1 (design.md §4;
 * {@code contracts/access-facts/v1/schema.json}, ADR-034). Built from the account's current facts only. F7 checks
 * that every message built here is valid version 1 and carries every field Access reads; F15 checks the schema allows
 * no free text. Add only (ADR-016): change the contract files in the same commit (CLAUDE.md rule 7).
 */
public final class AccessFactsMessages {

    /** The contract version this class builds. */
    public static final int SCHEMA_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record AccessFacts(int schemaVersion, UUID accountId, long sequence, Instant producedAt, String standing,
            List<SourceFact> sources) {
    }

    record SourceFact(String kind, UUID id, String state) {
    }

    private AccessFactsMessages() {
    }

    /** The message for these facts, as JSON. The facts must have a sequence of at least 1. */
    public static String json(AccountFacts facts) {
        if (facts.sequence() < 1 || facts.producedAt() == null) {
            throw new IllegalArgumentException("No facts to send yet for account " + facts.accountId());
        }
        return JSON.writeValueAsString(new AccessFacts(SCHEMA_VERSION, facts.accountId(), facts.sequence(),
                facts.producedAt(), facts.standing().name(), facts.sources().stream().map(AccessFactsMessages::fact)
                        .toList()));
    }

    private static SourceFact fact(Source source) {
        return new SourceFact(source.kind().name(), source.id(), source.state().name());
    }
}
