package io.casehub.workers.http;

import io.casehub.workers.common.WorkerRuntime;
import io.casehub.workers.common.WorkerRuntimeStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class HttpWorkerRuntime implements WorkerRuntime {

    private final HttpEndpointResolver resolver;
    private volatile WorkerRuntimeStatus status = WorkerRuntimeStatus.PENDING;

    @Inject
    HttpWorkerRuntime(HttpEndpointResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String workerType() {
        return HttpWorkerConstants.WORKER_TYPE;
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
                resolver.initialize();
                status = WorkerRuntimeStatus.RUNNING;
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
