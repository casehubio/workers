package io.casehub.workers.k8s;

import io.casehub.workers.common.WorkerProvisioningException;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class K8sClusterManagerTest {

    private static JobDefinition defWithCluster(String cluster) {
        return new JobDefinition("test", "default", "image:latest",
            List.of(), List.of(), null, null, null, null, null,
            3600, 600, 0, 1_048_576, null, Map.of(), Map.of(),
            CleanupPolicy.DELETE, cluster);
    }

    @Test
    void clusterDefinitionRecordHoldsNameAndContext() {
        var def = new ClusterDefinition("prod", "prod-context");
        assertThat(def.name()).isEqualTo("prod");
        assertThat(def.context()).isEqualTo("prod-context");
    }

    @Test
    void clientForJobReturnsDefaultClientWhenClusterIsNull() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of(), defaultClient);

        assertThat(manager.clientForJob(defWithCluster(null))).isSameAs(defaultClient);
    }

    @Test
    void clientForJobReturnsDefaultClientWhenClusterIsBlank() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of(), defaultClient);

        assertThat(manager.clientForJob(defWithCluster(""))).isSameAs(defaultClient);
    }

    @Test
    void clientForJobReturnsNamedClusterClient() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        KubernetesClient prodClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of("prod", prodClient), defaultClient);

        assertThat(manager.clientForJob(defWithCluster("prod"))).isSameAs(prodClient);
    }

    @Test
    void clientForJobThrowsForUnknownCluster() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of(), defaultClient);

        assertThatThrownBy(() -> manager.clientForJob(defWithCluster("nonexistent")))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void clientForReturnsDefaultForNullOrEmpty() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of(), defaultClient);

        assertThat(manager.clientFor(null)).isSameAs(defaultClient);
        assertThat(manager.clientFor("")).isSameAs(defaultClient);
    }

    @Test
    void clientForReturnsNamedClient() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        KubernetesClient stagingClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of("staging", stagingClient), defaultClient);

        assertThat(manager.clientFor("staging")).isSameAs(stagingClient);
    }

    @Test
    void clusterNamesReturnsConfiguredNames() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(
            Map.of("prod", mock(KubernetesClient.class), "staging", mock(KubernetesClient.class)),
            defaultClient);

        assertThat(manager.clusterNames()).containsExactlyInAnyOrder("prod", "staging");
    }

    @Test
    void defaultClientReturnsInjectedClient() {
        KubernetesClient defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager = new K8sClusterManager();
        manager.initialize(Map.of(), defaultClient);

        assertThat(manager.defaultClient()).isSameAs(defaultClient);
    }

    @Test
    void initializeFromConfig_noClusterProperties_leavesEmpty() {
        org.eclipse.microprofile.config.Config mockConfig = mock(org.eclipse.microprofile.config.Config.class);
        doReturn(java.util.List.of("some.other.prop")).when(mockConfig).getPropertyNames();

        KubernetesClient  defaultClient = mock(KubernetesClient.class);
        K8sClusterManager manager       = new K8sClusterManager();
        manager.config = mockConfig;
        manager.initialize(Map.of(), defaultClient);

        manager.initializeFromConfig();

        assertThat(manager.clusterNames()).isEmpty();
    }
}
