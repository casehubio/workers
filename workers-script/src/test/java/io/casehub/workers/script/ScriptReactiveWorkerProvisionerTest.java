package io.casehub.workers.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.spi.ProvisionResult;
import io.casehub.workers.common.WorkerProvisioningException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScriptReactiveWorkerProvisionerTest {

    @Test
    void provision_knownCapability_returnsEmpty() {
        ScriptDefinitionResolver resolver = new ScriptDefinitionResolver();
        resolver.initialize(Map.of(
            "build", new ScriptDefinition("build", "make", List.of(), null, Map.of(), 300, 1_048_576)
        ));
        ScriptReactiveWorkerProvisioner provisioner = new ScriptReactiveWorkerProvisioner();
        provisioner.scriptDefinitionResolver = resolver;

        ProvisionResult result = provisioner
            .provision(Set.of("script:build"), null)
            .await().indefinitely();

        assertThat(result).isEqualTo(ProvisionResult.empty());
    }

    @Test
    void provision_unknownCapability_throws() {
        ScriptDefinitionResolver resolver = new ScriptDefinitionResolver();
        resolver.initialize(Map.of());
        ScriptReactiveWorkerProvisioner provisioner = new ScriptReactiveWorkerProvisioner();
        provisioner.scriptDefinitionResolver = resolver;

        assertThatThrownBy(() ->
            provisioner.provision(Set.of("script:missing"), null)
                .await().indefinitely())
            .isInstanceOf(WorkerProvisioningException.class);
    }

    @Test
    void getCapabilities_delegatesToResolver() {
        ScriptDefinitionResolver resolver = new ScriptDefinitionResolver();
        resolver.initialize(Map.of(
            "a", new ScriptDefinition("a", "cmd", List.of(), null, Map.of(), 300, 1_048_576),
            "b", new ScriptDefinition("b", "cmd", List.of(), null, Map.of(), 300, 1_048_576)
        ));
        ScriptReactiveWorkerProvisioner provisioner = new ScriptReactiveWorkerProvisioner();
        provisioner.scriptDefinitionResolver = resolver;

        Set<String> caps = provisioner.getCapabilities().await().indefinitely();

        assertThat(caps).containsExactlyInAnyOrder("script:a", "script:b");
    }
}
