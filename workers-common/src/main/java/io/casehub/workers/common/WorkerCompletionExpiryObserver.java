package io.casehub.workers.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkerCompletionExpiryObserver {

    @Inject
    WorkerFaultPublisher faultPublisher;

    void onExpiry(@ObservesAsync CompletionExpiredEvent event) {
        faultPublisher.fault(event.pending(),
            new RuntimeException("Async timeout — no completion received within TTL"));
    }
}
