package com.tally.payment.application;

import com.tally.payment.domain.CollectionRequests;
import com.tally.payment.domain.PaymentAttempt;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Keeps a collection request's outcome in step with its attempt (FR-041). Called inside the
 * transaction that changes the attempt: the request and its attempts are one aggregate.
 */
@Component
class CollectionRequestSettlement {

    private final CollectionRequests requests;
    private final Clock clock;

    CollectionRequestSettlement(CollectionRequests requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    /**
     * Returns the request's version after the change: the sequence of the event that reports the attempt's
     * outcome (ADR-016), so call it before publishing. Null for an attempt from before FR-041, with no request.
     */
    Long settle(PaymentAttempt attempt) {
        return attempt.requestId().flatMap(requests::find).map(request -> {
            request.settle(attempt.state(), clock.instant());
            requests.update(request);
            return request.version();
        }).orElse(null);
    }
}
