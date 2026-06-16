package io.casehub.workers.script;

import io.casehub.workers.common.WorkerRuntime;
import io.casehub.workers.common.WorkerRuntimeStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScriptWorkerRuntime implements WorkerRuntime {

    private static final Logger LOG = Logger.getLogger(ScriptWorkerRuntime.class);

    private final ScriptDefinitionResolver resolver;
    private volatile WorkerRuntimeStatus status = WorkerRuntimeStatus.PENDING;

    @Inject
    ScriptWorkerRuntime(ScriptDefinitionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String workerType() {
        return ScriptWorkerConstants.WORKER_TYPE;
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
            try {
                if (resolver.capabilities().isEmpty()) {
                    resolver.initialize();
                }
                if (resolver.capabilities().isEmpty()) {
                    LOG.warn("No scripts configured — status FAULTED");
                    status = WorkerRuntimeStatus.FAULTED;
                } else {
                    status = WorkerRuntimeStatus.RUNNING;
                }
            } catch (Exception e) {
                status = WorkerRuntimeStatus.FAULTED;
                throw e;
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
        return resolver.capabilities();
    }
}
