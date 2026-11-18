package com.tally.payment;

import java.util.Optional;
import java.util.UUID;

/**
 * A payment's latest attempt. {@code accountId} is the account the reference belongs to, added in Slice 4 so that the
 * web layer answers only for the caller's account (spike S-3): Payment holds one account per reference.
 */
public record PaymentStatus(String reference, AttemptState state, Optional<FailureReason> failureReason,
        UUID accountId) {
}
