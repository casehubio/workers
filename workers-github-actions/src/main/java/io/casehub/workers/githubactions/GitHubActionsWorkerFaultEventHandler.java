package io.casehub.workers.githubactions;

import io.casehub.workers.common.WorkerFaultEvent;
import io.casehub.workers.common.WorkerFaultHandler;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GitHubActionsWorkerFaultEventHandler {

    @Inject WorkerFaultHandler workerFaultHandler;

    @ConsumeEvent(value = GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT, blocking = true)
    public void onFault(WorkerFaultEvent event) {
        workerFaultHandler.handleFault(event);
    }
}
