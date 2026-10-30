package io.casehub.workers.k8s;

import io.casehub.workers.common.WorkerRuntime;
import io.casehub.workers.common.WorkerRuntimeStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class K8sWorkerRuntime implements WorkerRuntime {

    private static final Logger LOG = Logger.getLogger(K8sWorkerRuntime.class);

    @Inject JobDefinitionResolver resolver;
    @Inject K8sJobInformerManager informerManager;
    @Inject K8sClusterManager clusterManager;

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
                      clusterManager.initializeFromConfig();

                      if (resolver.capabilities().isEmpty()) {
                          status = WorkerRuntimeStatus.FAULTED;
                          LOG.warn("No K8s job definitions configured — runtime FAULTED");
                          return null;
                      }

                      Map<String, Set<String>> clusterNamespaces = buildClusterNamespaceMap();
                      Map<String, Set<String>> reachable         = new java.util.LinkedHashMap<>();

                      for (var entry : clusterNamespaces.entrySet()) {
                          String           clusterName = entry.getKey();
                          KubernetesClient client      = clusterManager.clientFor(clusterName);
                          try {
                              client.getApiVersion();
                              reachable.put(clusterName, entry.getValue());
                          } catch (Exception e) {
                              LOG.warnf("K8s cluster '%s' unreachable: %s",
                                        clusterName.isEmpty() ? "default" : clusterName, e.getMessage());
                          }
                      }

                      if (reachable.isEmpty()) {
                          status = WorkerRuntimeStatus.FAULTED;
                          LOG.warn("All K8s clusters unreachable — runtime FAULTED");
                          return null;
                      }

                      informerManager.start(reachable);

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

    private Map<String, Set<String>> buildClusterNamespaceMap() {
        Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
        for (String cap : resolver.capabilities()) {
            String        name    = cap.substring(K8sWorkerConstants.TAG_PREFIX.length());
            JobDefinition def     = resolver.resolve(cap, null);
            String        cluster = def.cluster() != null ? def.cluster() : "";
            result.computeIfAbsent(cluster, k -> new java.util.LinkedHashSet<>()).add(def.namespace());
        }
        return result;
    }

}
