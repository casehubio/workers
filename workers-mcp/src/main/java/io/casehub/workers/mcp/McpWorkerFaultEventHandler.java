package io.casehub.workers.mcp;

import io.casehub.workers.common.WorkerFaultEvent;
import io.casehub.workers.common.WorkerFaultHandler;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpWorkerFaultEventHandler {

    @Inject WorkerFaultHandler workerFaultHandler;

    @ConsumeEvent(value = McpWorkerEventBusAddresses.MCP_WORKER_FAULT, blocking = true)
    public void onFault(WorkerFaultEvent event) {
        workerFaultHandler.handleFault(event);
    }
}
