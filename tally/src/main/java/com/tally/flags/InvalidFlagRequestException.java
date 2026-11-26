package com.tally.flags;

/** A bad name, country or account id. */
public final class InvalidFlagRequestException extends RuntimeException {

    public InvalidFlagRequestException(String message) {
        super(message);
    }
}
