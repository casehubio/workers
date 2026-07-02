package io.casehub.workers.k8s;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.workers.common.WorkerProvisioningException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

@ApplicationScoped
public class K8sReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    @Inject
    JobDefinitionResolver resolver;

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        String tenancyId = context.tenancyId();
        String capability = resolver.firstMatch(capabilities, tenancyId)
            .orElseThrow(() -> WorkerProvisioningException.noRouteFound(capabilities.toString()));
        resolver.resolve(capability, tenancyId);
        return Uni.createFrom().item(ProvisionResult.empty());
    }

    @Override
    public Uni<Void> terminate(String workerId, String tenancyId) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(resolver.capabilities());
    }
}
