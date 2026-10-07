package com.fakeprovider;

import java.time.Instant;

/** A charge the provider recorded: the truth that Tally's reconciliation reads. */
public record Charge(String chargeId, String reference, String token, long amount, String currency,
        Outcome outcome, Instant createdAt) {

    public enum Outcome {
        SUCCEEDED, DECLINED_SOFT, DECLINED_HARD
    }
}
