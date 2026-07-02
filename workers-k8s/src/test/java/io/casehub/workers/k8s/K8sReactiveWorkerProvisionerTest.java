package io.casehub.workers.k8s;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.workers.common.WorkerProvisioningException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class K8sReactiveWorkerProvisionerTest {

    private static JobDefinition imageDef(String name) {
        return new JobDefinition(name, "ns", "img:v1",
            List.of(), List.of(), null, null, null, null, null,
            3600, 600, 0, 1_048_576, null, Map.of(), Map.of(), CleanupPolicy.DELETE);
    }

    @Test
    void provision_knownCapability_returnsEmpty() {
        JobDefinitionResolver resolver = new JobDefinitionResolver();
        resolver.initialize(Map.of("build", imageDef("build")));
        K8sReactiveWorkerProvisioner provisioner = new K8sReactiveWorkerProvisioner();
        provisioner.resolver = resolver;

        ProvisionResult result = provisioner
            .provision(Set.of("k8s:build"),
                new ProvisionContext(UUID.randomUUID(), "platform", "task", null, null, null, null))
            .await().indefinitely();

        assertThat(result).isEqualTo(ProvisionResult.empty());
    }

    @Test
    void provision_unknownCapability_throws() {
        JobDefinitionResolver resolver = new JobDefinitionResolver();
        resolver.initialize(Map.of());
        K8sReactiveWorkerProvisioner provisioner = new K8sReactiveWorkerProvisioner();
        provisioner.resolver = resolver;

        assertThatThrownBy(() ->
            provisioner.provision(Set.of("k8s:missing"),
                new ProvisionContext(UUID.randomUUID(), "platform", "task", null, null, null, null))
                .await().indefinitely())
            .isInstanceOf(WorkerProvisioningException.class);
    }

    @Test
    void getCapabilities_delegatesToResolver() {
        JobDefinitionResolver resolver = new JobDefinitionResolver();
        resolver.initialize(Map.of(
            "a", imageDef("a"),
            "b", imageDef("b")
        ));
        K8sReactiveWorkerProvisioner provisioner = new K8sReactiveWorkerProvisioner();
        provisioner.resolver = resolver;

        Set<String> caps = provisioner.getCapabilities().await().indefinitely();

        assertThat(caps).containsExactlyInAnyOrder("k8s:a", "k8s:b");
    }
}
