package io.casehub.workers.common;

import io.casehub.api.model.Capability;
import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkerFaultPublisher {

    @Inject
    EventBus eventBus;

    public void fault(String faultAddress, WorkerCorrelationContext ctx,
                      Capability capability, Long eventLogId, Throwable cause) {
        eventBus.publish(faultAddress, new WorkflowExecutionFailed(
            ctx.caseInstance(), ctx.worker(), capability,
            ctx.idempotency(), eventLogId.toString(), cause));
    }

    public void fault(PendingCompletion pending, Throwable cause) {
        eventBus.publish(pending.faultAddress(), new WorkflowExecutionFailed(
            pending.correlationContext().caseInstance(),
            pending.correlationContext().worker(),
            pending.capability(),
            pending.correlationContext().idempotency(),
            pending.eventLogId().toString(),
            cause));
    }
}
