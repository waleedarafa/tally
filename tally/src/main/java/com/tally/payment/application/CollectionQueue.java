package com.tally.payment.application;

import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.QueueDefinition;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Payment's durable queue of attempts to make (ADR-017), with its own worker pool: the bulkhead (ADR-005). */
@Component
class CollectionQueue {

    // The permanent guard (ADR-037, ADR-041): one collection request per reference, never deleted. Each
    // item names one attempt of that request, sent only by its PENDING to IN_FLIGHT transition.
    static final QueueDefinition DEFINITION = QueueDefinition.of("payment-collection", "payment.collection_queue",
            "payment.collection_request_one_per_reference");

    private final DurableQueue queue;
    private final Clock clock;

    CollectionQueue(DurableQueues queues, CollectionWorker worker, ProviderIsolation isolation,
            PaymentProperties properties, Clock clock) {
        // While the provider's circuit breaker is open, workers stop claiming (brief section 5).
        this.queue = queues.register(DEFINITION.withWorkers(properties.workers()), worker, isolation);
        this.clock = clock;
    }

    void enqueue(UUID attemptId) {
        queue.enqueue(attemptId.toString(), clock.instant(), "");
    }
}
