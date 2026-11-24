package com.tally.invoicing;

import java.util.UUID;

public class UpgradeAlreadyInProgressException extends RuntimeException {

    public UpgradeAlreadyInProgressException(UUID subscriptionId, String inFlightPlanId) {
        super("Subscription " + subscriptionId + " already has an upgrade to " + inFlightPlanId + " in flight");
    }
}
