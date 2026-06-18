package io.casehub.workers.camel;

import io.casehub.workers.common.WorkerCapabilityResolver;
import io.casehub.workers.common.WorkerProvisioningException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.ExchangePattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class CamelCapabilityResolver implements WorkerCapabilityResolver<String> {

    @Inject
    CamelContext camelContext;

    @Inject @Any
    Instance<CamelWorkerRoute> spiRoutes;

    @ConfigProperty(name = "casehub.workers.camel.capabilities", defaultValue = "")
    Map<String, String> configCapabilities;

    private final Map<String, String> resolvedRoutes = new HashMap<>();
    private final Map<String, ExchangePattern> exchangePatterns = new HashMap<>();

    void initialize() {
        resolvedRoutes.clear();
        exchangePatterns.clear();

        // Tier 1: SPI-registered CamelWorkerRoute beans
        for (CamelWorkerRoute route : spiRoutes) {
            resolvedRoutes.put(route.capabilityTag(), route.entryUri());
            exchangePatterns.put(route.capabilityTag(), route.exchangePattern());
        }

        // Tier 2: Config-based capabilities
        if (configCapabilities != null) {
            configCapabilities.forEach((tag, uri) -> {
                resolvedRoutes.putIfAbsent(tag, uri);
                exchangePatterns.putIfAbsent(tag, ExchangePattern.InOnly);
            });
        }

        // Tier 3: Convention — route ID = capability tag AND from: direct:{capabilityTag}
        camelContext.getRoutes().forEach(route -> {
            String routeId = route.getRouteId();
            if (routeId != null && !resolvedRoutes.containsKey(routeId)) {
                String fromUri = route.getEndpoint().getEndpointUri();
                // fromUri can be "direct:send-email" or "direct://send-email"
                String expectedPattern = "direct:" + routeId;
                String expectedPatternAlt = "direct://" + routeId;
                if (fromUri.equals(expectedPattern) || fromUri.equals(expectedPatternAlt)) {
                    resolvedRoutes.put(routeId, "direct:" + routeId);
                    exchangePatterns.putIfAbsent(routeId, ExchangePattern.InOnly);
                }
            }
        });
    }

    @Override
    public String resolve(String capabilityTag, String tenancyId) {
        String uri = resolvedRoutes.get(capabilityTag);
        if (uri == null) {
            throw WorkerProvisioningException.noRouteFound(capabilityTag);
        }
        return uri;
    }

    @Override
    public Optional<String> firstMatch(Set<String> capabilities, String tenancyId) {
        return capabilities.stream()
            .filter(resolvedRoutes::containsKey)
            .findFirst();
    }

    @Override
    public Set<String> capabilities() {
        return Set.copyOf(resolvedRoutes.keySet());
    }

    public ExchangePattern exchangePattern(String capabilityTag) {
        return exchangePatterns.getOrDefault(capabilityTag, ExchangePattern.InOnly);
    }
}
