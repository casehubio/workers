package io.casehub.workers.githubactions;

import io.casehub.workers.common.WorkerRuntime;
import io.casehub.workers.common.WorkerRuntimeStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubActionsWorkerRuntime implements WorkerRuntime {

    private static final Logger LOG = Logger.getLogger(GitHubActionsWorkerRuntime.class);

    private final GitHubActionsTokenResolver tokenResolver;
    private volatile WorkerRuntimeStatus status = WorkerRuntimeStatus.PENDING;

    @Inject
    GitHubActionsWorkerRuntime(GitHubActionsTokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    @Override
    public String workerType() {
        return GitHubActionsWorkerConstants.WORKER_TYPE;
    }

    @Override
    public WorkerRuntimeStatus status() {
        return status;
    }

    @Override
    public Uni<Void> initialize() {
        if (status == WorkerRuntimeStatus.RUNNING) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().item(() -> {
            if (tokenResolver.hasToken()) {
                status = WorkerRuntimeStatus.RUNNING;
            } else {
                LOG.warn("GitHub Actions worker has no configured token — status FAULTED");
                status = WorkerRuntimeStatus.FAULTED;
            }
            return null;
        }).replaceWithVoid();
    }

    @Override
    public Uni<Void> shutdown() {
        status = WorkerRuntimeStatus.STOPPED;
        return Uni.createFrom().voidItem();
    }

    @Override
    public Set<String> capabilities() {
        if (status != WorkerRuntimeStatus.RUNNING) {
            return Set.of();
        }
        return Set.of(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH,
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH
        );
    }
}
