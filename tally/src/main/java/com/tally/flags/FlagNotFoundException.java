package com.tally.flags;

/** The flag does not exist. */
public final class FlagNotFoundException extends RuntimeException {

    public FlagNotFoundException(String message) {
        super(message);
    }
}
