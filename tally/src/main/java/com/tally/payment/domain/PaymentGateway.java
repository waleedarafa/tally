package com.tally.payment.domain;

import com.tally.payment.FailureReason;
import com.tally.shared.Money;

/** Port to the payment provider, behind an anticorruption layer (ADR-014). */
public interface PaymentGateway {

    /**
     * Charges the token. Throws when the outcome is not known: a timeout, a dropped connection or
     * an error response. The caller must then treat the charge as possibly made (ADR-018).
     */
    ChargeResult charge(String paymentToken, Money amount, String reference);

    sealed interface ChargeResult {
    }

    record Charged(String chargeId) implements ChargeResult {
    }

    record Declined(FailureReason reason) implements ChargeResult {
    }
}
