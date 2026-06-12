package io.casehub.workers.mcp;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.workers.common.WorkerProvisioningException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

@ApplicationScoped
public class McpReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    @Inject
    McpServerResolver serverResolver;

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        boolean match = capabilities.stream().anyMatch(serverResolver.capabilities()::contains);
        if (!match) {
            throw new WorkerProvisioningException(
                "No matching MCP capability. Supported: " + serverResolver.capabilities());
        }
        return Uni.createFrom().item(ProvisionResult.empty());
    }

    @Override
    public Uni<Void> terminate(String workerId) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(serverResolver.capabilities());
    }
}
