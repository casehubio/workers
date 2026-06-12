package io.casehub.workers.mcp;

import io.casehub.api.model.Capability;
import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpWorkerFaultPublisher {

    @Inject
    EventBus eventBus;

    public void fault(WorkerCorrelationContext ctx, Capability capability,
                      Long eventLogId, Throwable cause) {
        eventBus.publish(McpWorkerEventBusAddresses.MCP_WORKER_FAULT,
            new WorkflowExecutionFailed(
                ctx.caseInstance(), ctx.worker(), capability,
                ctx.idempotency(), eventLogId.toString(), cause));
    }
}
