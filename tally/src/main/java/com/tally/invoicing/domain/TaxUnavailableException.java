package com.tally.invoicing.domain;

/** The tax service could not be reached or answered (ADR-050): the queue, not the invoice, is the retry. */
public class TaxUnavailableException extends RuntimeException {

    public TaxUnavailableException(String message) {
        super(message);
    }

    public TaxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
