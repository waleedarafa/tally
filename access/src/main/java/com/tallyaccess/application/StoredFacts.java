package com.tallyaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One account's facts, as Access stores them: the message with the highest sequence it has applied. */
public record StoredFacts(UUID accountId, long sequence, Instant producedAt, String standing,
        List<AccessFactsMessage.Source> sources) {
}
