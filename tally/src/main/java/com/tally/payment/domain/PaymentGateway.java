package com.tally.payment.domain;

import com.tally.payment.FailureReason;
import com.tally.shared.Money;
import java.time.Duration;
import java.util.List;

/** Port to the payment provider, behind an anticorruption layer (FR-014). */
public interface PaymentGateway {

    /**
     * Charges the token.
     *
     * @throws NotChargedException when the provider certainly did not charge: it refused the request
     *         (429, 503) or the request never reached it. Safe to try again later.
     * @throws RuntimeException for anything else, such as a read timeout or an unexpected answer: the
     *         charge may have been made, so the outcome is unknown (FR-018).
     */
    ChargeResult charge(String paymentToken, Money amount, String reference);

    /** The provider's record of charges for a reference: the truth reconciliation reads (FR-018). */
    List<RecordedCharge> chargesFor(String reference);

    record RecordedCharge(String chargeId, ChargeResult result) {
    }

    sealed interface ChargeResult {
    }

    record Charged(String chargeId) implements ChargeResult {
    }

    record Declined(FailureReason reason) implements ChargeResult {
    }

    /** The provider certainly did not charge. {@code retryAfter} is the earliest sensible retry. */
    final class NotChargedException extends RuntimeException {

        public enum Why {
            /** 429 from the provider. Not a provider failure. */
            RATE_LIMITED,
            /** 503, or the request never reached the provider. */
            UNAVAILABLE,
            /** Tally held the call back: breaker open or local rate limit. The provider was not called. */
            HELD_BACK
        }

        private final Why why;
        private final Duration retryAfter;

        public NotChargedException(Why why, Duration retryAfter, String message, Throwable cause) {
            super(message, cause);
            this.why = why;
            this.retryAfter = retryAfter;
        }

        public Why why() {
            return why;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}
