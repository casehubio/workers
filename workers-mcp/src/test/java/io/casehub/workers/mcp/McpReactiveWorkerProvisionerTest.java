package io.casehub.workers.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.workers.common.WorkerProvisioningException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpReactiveWorkerProvisionerTest {

    private McpReactiveWorkerProvisioner provisioner;
    private McpServerResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new McpServerResolver();
        resolver.initialize(List.of(
            new McpServerResolver.ServerConfig("slack", "https://slack.internal/mcp", "send-message,list-channels", 30, Map.of())
        ), 30);

        provisioner = new McpReactiveWorkerProvisioner();
        provisioner.serverResolver = resolver;
    }

    @Test
    void getCapabilities_returnsAllTags() {
        assertThat(provisioner.getCapabilities().await().indefinitely())
            .containsExactlyInAnyOrder("mcp:slack:send-message", "mcp:slack:list-channels");
    }

    @Test
    void provision_matchingCapability_succeeds() {
        ProvisionContext ctx = new ProvisionContext(UUID.randomUUID(), "task", null, null, null, null);
        ProvisionResult result = provisioner.provision(
            Set.of("mcp:slack:send-message"), ctx).await().indefinitely();
        assertThat(result).isNotNull();
    }

    @Test
    void provision_unknownCapability_throws() {
        ProvisionContext ctx = new ProvisionContext(UUID.randomUUID(), "task", null, null, null, null);
        assertThatThrownBy(() -> provisioner.provision(
            Set.of("mcp:unknown:tool"), ctx).await().indefinitely())
            .isInstanceOf(WorkerProvisioningException.class);
    }

    @Test
    void terminate_returnsVoid() {
        assertThat(provisioner.terminate("any").await().indefinitely()).isNull();
    }
}
