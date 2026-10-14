package com.fakeprovider;

import java.util.List;

/**
 * One settlement batch: the charges whose money the provider moved between two cut-offs (CONTRACT.md A15). It
 * carries no time: Tally must not read the fake's wall clock (section 7), so batches are ordered by number only.
 */
public record SettlementBatch(long number, List<Line> lines) {

    public record Line(String chargeId, String reference, long amount, String currency) {
    }
}
