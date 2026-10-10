package com.tally.payment.application;

import com.tally.payment.domain.CollectionRequestRepository;
import com.tally.payment.domain.PaymentAttempt;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Keeps a collection request's outcome in step with its attempt (FR-041). Called inside the
 * transaction that changes the attempt: the request and its attempts are one aggregate.
 */
@Component
class CollectionRequests {

    private final CollectionRequestRepository requests;
    private final Clock clock;

    CollectionRequests(CollectionRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    void settle(PaymentAttempt attempt) {
        attempt.requestId().flatMap(requests::findById)
                .ifPresent(request -> request.settle(attempt.state(), clock.instant()));
    }
}
