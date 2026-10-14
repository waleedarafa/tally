package com.tally.payment.domain;

import com.tally.shared.Money;
import java.util.List;

/**
 * One of the provider's settlement batches: the charges whose money it moved between two cut-offs (proposed 021;
 * CONTRACT.md A15). Batches are numbered in order; Tally reads them by number, never by the provider's time.
 */
public record SettlementBatch(long number, List<Line> lines) {

    public SettlementBatch {
        lines = List.copyOf(lines);
    }

    /** A settled charge: the provider's charge ID, Tally's reference, and the amount moved. */
    public record Line(String chargeId, String reference, Money amount) {
    }
}
