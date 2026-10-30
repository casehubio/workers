package io.casehub.workers.k8s;

import io.casehub.workers.common.WorkerProvisioningException;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class K8sClusterManager {
    @Inject
    org.eclipse.microprofile.config.Config config;


    private static final Logger LOG = Logger.getLogger(K8sClusterManager.class);

    @Inject
    KubernetesClient defaultClient;

    private volatile Map<String, KubernetesClient> clients = Map.of();

    void initialize(Map<String, KubernetesClient> namedClients, KubernetesClient defaultClient) {
        this.clients = Map.copyOf(namedClients);
        this.defaultClient = defaultClient;
    }

    void initializeFromConfig() {
        if (config == null) {return;}
        String                                  prefix          = "casehub.workers.k8s.clusters.";
        java.util.LinkedHashMap<String, String> clusterContexts = new java.util.LinkedHashMap<>();

        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix)) {
                String remainder = key.substring(prefix.length());
                int    dot       = remainder.indexOf('.');
                if (dot > 0) {
                    String clusterName = remainder.substring(0, dot);
                    String prop        = remainder.substring(dot + 1);
                    if ("context".equals(prop)) {
                        config.getOptionalValue(key, String.class).ifPresent(value ->
                                                                                     clusterContexts.put(clusterName, value));
                    }
                }
            }
        }

        if (clusterContexts.isEmpty()) {return;}

        java.util.LinkedHashMap<String, KubernetesClient> namedClients = new java.util.LinkedHashMap<>();
        for (var entry : clusterContexts.entrySet()) {
            try {
                io.fabric8.kubernetes.client.Config clientConfig =
                        io.fabric8.kubernetes.client.Config.autoConfigure(entry.getValue());
                KubernetesClient client = new io.fabric8.kubernetes.client.KubernetesClientBuilder()
                                                  .withConfig(clientConfig)
                                                  .build();
                namedClients.put(entry.getKey(), client);
                LOG.infof("Created KubernetesClient for cluster '%s' (context: %s)",
                          entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LOG.warnf("Failed to create KubernetesClient for cluster '%s' (context: %s): %s",
                          entry.getKey(), entry.getValue(), e.getMessage());
            }
        }
        this.clients = Map.copyOf(namedClients);
    }


    public KubernetesClient clientForJob(JobDefinition definition) {
        String cluster = definition.cluster();
        if (cluster == null || cluster.isBlank()) {
            return defaultClient;
        }
        KubernetesClient client = clients.get(cluster);
        if (client == null) {
            throw new WorkerProvisioningException(
                "Unknown K8s cluster '" + cluster + "' — not configured in casehub.workers.k8s.clusters");
        }
        return client;
    }

    public KubernetesClient clientFor(String clusterName) {
        if (clusterName == null || clusterName.isEmpty()) {
            return defaultClient;
        }
        KubernetesClient client = clients.get(clusterName);
        if (client == null) {
            throw new WorkerProvisioningException(
                "Unknown K8s cluster '" + clusterName + "' — not configured in casehub.workers.k8s.clusters");
        }
        return client;
    }

    public KubernetesClient defaultClient() {
        return defaultClient;
    }

    public Set<String> clusterNames() {
        return clients.keySet();
    }

    public void shutdown() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warnf("Failed to close cluster client: %s", e.getMessage());
            }
        });
    }
}
