package io.casehub.workers.githubactions;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.workers.common.WorkerProvisioningException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

@ApplicationScoped
public class GitHubActionsReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    private static final Set<String> CAPABILITIES = Set.of(
        GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH,
        GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

    @Inject
    GitHubActionsTokenResolver tokenResolver;

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        boolean match = capabilities.stream().anyMatch(CAPABILITIES::contains);
        if (!match) {
            throw new WorkerProvisioningException(
                "No matching GitHub Actions capability. Supported: " + CAPABILITIES);
        }
        if (!tokenResolver.hasToken()) {
            throw new WorkerProvisioningException(
                "GitHub Actions worker requires a configured token "
                    + "(casehub.workers.github-actions.token)");
        }
        return Uni.createFrom().item(ProvisionResult.empty());
    }

    @Override
    public Uni<Void> terminate(String workerId, String tenancyId) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(CAPABILITIES);
    }
}
