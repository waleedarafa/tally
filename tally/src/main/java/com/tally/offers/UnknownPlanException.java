package com.tally.offers;

import java.util.UUID;

public class UnknownPlanException extends RuntimeException {

    public UnknownPlanException(String planId, String currency) {
        super("No plan " + planId + " priced in " + currency);
    }

    public UnknownPlanException(String offerId, UUID accountId) {
        super("Offer " + offerId + " is not offered to account " + accountId);
    }
}
