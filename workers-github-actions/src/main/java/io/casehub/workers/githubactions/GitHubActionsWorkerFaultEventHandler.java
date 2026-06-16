package io.casehub.workers.githubactions;

import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.casehub.workers.common.WorkerFaultHandler;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GitHubActionsWorkerFaultEventHandler {

    @Inject WorkerFaultHandler workerFaultHandler;

    @ConsumeEvent(value = GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT, blocking = true)
    public Uni<Void> onFault(WorkflowExecutionFailed event) {
        return workerFaultHandler.handleFault(event);
    }
}
