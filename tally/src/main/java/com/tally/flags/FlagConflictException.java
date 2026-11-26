package com.tally.flags;

/** A change the rules refuse. */
public final class FlagConflictException extends RuntimeException {

    public FlagConflictException(String message) {
        super(message);
    }
}
