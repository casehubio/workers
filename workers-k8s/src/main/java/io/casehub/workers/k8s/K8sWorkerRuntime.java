package io.casehub.workers.k8s;

import io.casehub.workers.common.WorkerRuntime;
import io.casehub.workers.common.WorkerRuntimeStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class K8sWorkerRuntime implements WorkerRuntime {

    private static final Logger LOG = Logger.getLogger(K8sWorkerRuntime.class);

    @Inject JobDefinitionResolver resolver;
    @Inject K8sJobInformerManager informerManager;
    @Inject KubernetesClient kubernetesClient;

    private volatile WorkerRuntimeStatus status = WorkerRuntimeStatus.PENDING;

    @Override
    public String workerType() {
        return K8sWorkerConstants.WORKER_TYPE;
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
            if (resolver.capabilities().isEmpty()) {
                status = WorkerRuntimeStatus.FAULTED;
                LOG.warn("No K8s job definitions configured — runtime FAULTED");
                return null;
            }

            try {
                kubernetesClient.getApiVersion();
            } catch (Exception e) {
                status = WorkerRuntimeStatus.FAULTED;
                LOG.warnf("K8s cluster unreachable: %s — runtime FAULTED", e.getMessage());
                return null;
            }

            Set<String> namespaces = resolver.namespaces();
            informerManager.start(namespaces);

            if (!informerManager.hasActiveInformers()) {
                status = WorkerRuntimeStatus.FAULTED;
                LOG.warn("All namespace informers failed — runtime FAULTED");
                return null;
            }

            status = WorkerRuntimeStatus.RUNNING;
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
          .replaceWithVoid();
    }

    @Override
    public Uni<Void> shutdown() {
        return Uni.createFrom().item(() -> {
            informerManager.stop();
            status = WorkerRuntimeStatus.STOPPED;
            return null;
        }).replaceWithVoid();
    }

    @Override
    public Set<String> capabilities() {
        return resolver.capabilities();
    }
}
