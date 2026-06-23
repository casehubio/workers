package io.casehub.workers.http;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.workers.common.WorkerProvisioningException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

@ApplicationScoped
public class HttpReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    @Inject
    HttpEndpointResolver httpEndpointResolver;

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        String tenancyId = context.tenancyId();
        String capability = httpEndpointResolver.firstMatch(capabilities, tenancyId)
            .orElseThrow(() -> WorkerProvisioningException.noRouteFound(capabilities.toString()));
        httpEndpointResolver.resolve(capability, tenancyId);
        return Uni.createFrom().item(ProvisionResult.empty());
    }

    @Override
    public Uni<Void> terminate(String workerId, String tenancyId) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(httpEndpointResolver.capabilities());
    }
}
