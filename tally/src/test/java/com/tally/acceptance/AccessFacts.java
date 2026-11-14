package com.tally.acceptance;

import java.time.Instant;
import java.util.UUID;

/** An {@code AccessFacts} message as billing's relay writes it (contracts/access-facts/v1/), for tests that write to the log. */
final class AccessFacts {

    private static final Instant FIRST = Instant.parse("2026-11-14T06:00:00Z");

    private AccessFacts() {
    }

    /** The facts of one account at a sequence, with one subscription in each given state; producedAt follows the sequence. */
    static String json(UUID account, long sequence, String standing, String... sourceStates) {
        StringBuilder sources = new StringBuilder();
        for (String state : sourceStates) {
            if (!sources.isEmpty()) {
                sources.append(',');
            }
            sources.append("{\"kind\":\"SUBSCRIPTION\",\"id\":\"").append(UUID.nameUUIDFromBytes(account.toString()
                    .getBytes())).append("\",\"state\":\"").append(state).append("\"}");
        }
        return "{\"schemaVersion\":1,\"accountId\":\"" + account + "\",\"sequence\":" + sequence + ",\"producedAt\":\""
                + FIRST.plusSeconds(sequence) + "\",\"standing\":\"" + standing + "\",\"sources\":[" + sources + "]}";
    }
}
