package com.tally.payment.domain;

/** What settlement reconciliation found that a person must look at (proposed 021; D14). */
public enum MismatchKind {

    /** The provider settled a charge for which Tally has no successful attempt, and no UNKNOWN one to decide. */
    CHARGED_NOT_RECORDED,

    /** The provider settled another amount than Tally's attempt for the charge or reference. Nothing is decided. */
    AMOUNT_DIFFERS,

    /** Tally recorded a success, and no batch shows its charge after the settlement delay. */
    RECORDED_NOT_SETTLED,

    /**
     * An UNKNOWN attempt, and no batch shows a charge for its reference after the settlement delay. Probably not
     * charged, but "not found" alone never decides (026): a person does.
     */
    UNKNOWN_NOT_SETTLED
}
