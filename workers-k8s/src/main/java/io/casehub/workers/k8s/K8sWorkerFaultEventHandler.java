package io.casehub.workers.k8s;

import io.casehub.workers.common.WorkerFaultEvent;
import io.casehub.workers.common.WorkerFaultHandler;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class K8sWorkerFaultEventHandler {

    @Inject WorkerFaultHandler workerFaultHandler;

    @ConsumeEvent(value = K8sWorkerEventBusAddresses.K8S_WORKER_FAULT, blocking = true)
    public Uni<Void> onFault(WorkerFaultEvent event) {
        return workerFaultHandler.handleFault(event);
    }
}
