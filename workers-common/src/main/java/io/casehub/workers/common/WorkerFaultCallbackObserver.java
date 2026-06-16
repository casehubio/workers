package io.casehub.workers.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkerFaultCallbackObserver {

    @Inject
    WorkerFaultPublisher faultPublisher;

    void onFaultCallback(@ObservesAsync FaultCallbackEvent event) {
        faultPublisher.fault(event.pending(), event.cause());
    }
}
