package com.tally.invoicing.domain;

/**
 * The tax service, or the settings, refused the request in a way a retry will not fix: a client error, an
 * unreadable or unknown answer, a jurisdiction with no rate. The invoice stays DRAFT and its tax-quote item
 * FAILS, so it pages (ADR-050).
 */
public class TaxQuoteRefusedException extends RuntimeException {

    public TaxQuoteRefusedException(String message) {
        super(message);
    }

    public TaxQuoteRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
