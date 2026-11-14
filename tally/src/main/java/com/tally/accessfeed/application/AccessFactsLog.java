package com.tally.accessfeed.application;

import java.util.UUID;

/** The event log, as the relay sees it: one message per send, keyed by the account (ADR-012). */
public interface AccessFactsLog {

    /**
     * Sends a message and returns once the log has acknowledged it.
     *
     * @throws LogUnavailable if the log did not acknowledge it in time; the message may still have reached it
     */
    void send(UUID accountId, String message) throws LogUnavailable;

    /** The log did not acknowledge a message. Not a failure of the facts: the relay tries again. */
    final class LogUnavailable extends Exception {

        public LogUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
