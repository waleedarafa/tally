package com.tallyaccess.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AccessFacts, as Access reads it (contracts/access-facts/v1, ADR-034). Its fields are exactly what Access reads:
 * {@code ConsumerAccessFieldsTest} checks them against {@code consumer-access.json}, which billing's F7 checks every
 * message against. A field it does not know is ignored; the enums are read as text, so a value of a later version is
 * kept and never refused (ADR-016, ADR-033). Access never checks a message against {@code schema.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessFactsMessage(Integer schemaVersion, UUID accountId, Long sequence, Instant producedAt,
        String standing, List<Source> sources) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String kind, UUID id, String state) {
    }

    /** Whether Access can store it: an account, a sequence and a time. Anything else it can read without. */
    public boolean complete() {
        return accountId != null && sequence != null && sequence >= 1 && producedAt != null;
    }

    public List<Source> sourcesOrNone() {
        return sources == null ? List.of() : sources;
    }
}
