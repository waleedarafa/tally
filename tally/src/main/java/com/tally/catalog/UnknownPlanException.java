package com.tally.catalog;

public class UnknownPlanException extends RuntimeException {

    public UnknownPlanException(String planId, String currency) {
        super("No plan " + planId + " priced in " + currency);
    }
}
