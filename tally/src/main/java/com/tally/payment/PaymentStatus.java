package com.tally.payment;

import java.util.Optional;

public record PaymentStatus(String reference, AttemptState state, Optional<FailureReason> failureReason) {
}
