package io.casehub.workers.http;

import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.path.Path;
import io.casehub.workers.common.WorkerCapabilityResolver;
import io.casehub.workers.common.WorkerProvisioningException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class HttpEndpointResolver implements WorkerCapabilityResolver<ResolvedEndpoint> {

    @Inject @Any
    Instance<HttpWorkerRoute> spiRoutes;

    @Inject
    Config config;

    @Inject
    EndpointRegistry endpointRegistry;

    @ConfigProperty(name = "casehub.workers.http.default-timeout-seconds", defaultValue = "30")
    int defaultTimeoutSeconds;

    private final Map<String, ResolvedEndpoint> resolvedEndpoints = new HashMap<>();
    private EndpointRegistry registry;

    /**
     * CDI startup entry point — gathers injected fields and delegates.
     */
    void initialize() {
        Map<String, Map<String, String>> configEndpoints = loadConfigEndpoints();
        List<HttpWorkerRoute> routes = List.of();
        if (spiRoutes != null && !spiRoutes.isUnsatisfied()) {
            routes = spiRoutes.stream().toList();
        }
        initialize(routes, configEndpoints, defaultTimeoutSeconds, endpointRegistry);
    }

    /**
     * Test-friendly initializer. Accepts all inputs explicitly so tests
     * can call without CDI.
     */
    void initialize(List<HttpWorkerRoute> spiRouteList,
                    Map<String, Map<String, String>> configEndpoints,
                    int defaultTimeout,
                    EndpointRegistry registry) {
        resolvedEndpoints.clear();
        this.registry = registry;
        this.defaultTimeoutSeconds = defaultTimeout;

        // Tier 1: SPI-registered HttpWorkerRoute beans (highest priority)
        for (HttpWorkerRoute route : spiRouteList) {
            int timeout = route.timeoutSeconds() == -1 ? defaultTimeout : route.timeoutSeconds();
            resolvedEndpoints.put(route.capabilityTag(), new ResolvedEndpoint(
                route.url(),
                route.method(),
                route.exchangeMode(),
                route.headers(),
                timeout
            ));
        }

        // Tier 2: Configuration properties (putIfAbsent — Tier 1 wins)
        if (configEndpoints != null) {
            configEndpoints.forEach((tag, props) -> {
                resolvedEndpoints.putIfAbsent(tag, buildFromConfig(props, defaultTimeout));
            });
        }

        // Tier 3: EndpointRegistry — resolved dynamically at lookup time.
        // resolve() and firstMatch() query the registry for tags not in the static map.
    }

    @Override
    public ResolvedEndpoint resolve(String capabilityTag, String tenancyId) {
        ResolvedEndpoint endpoint = resolvedEndpoints.get(capabilityTag);
        if (endpoint != null) {
            return endpoint;
        }
        if (registry != null) {
            endpoint = resolveFromRegistry(capabilityTag, tenancyId);
            if (endpoint != null) {
                return endpoint;
            }
        }
        throw WorkerProvisioningException.noRouteFound(capabilityTag);
    }

    @Override
    public Optional<String> firstMatch(Set<String> capabilities, String tenancyId) {
        Optional<String> staticMatch = capabilities.stream()
            .filter(resolvedEndpoints::containsKey)
            .findFirst();
        if (staticMatch.isPresent()) {
            return staticMatch;
        }
        if (registry != null) {
            for (String cap : capabilities) {
                if (resolveFromRegistry(cap, tenancyId) != null) {
                    return Optional.of(cap);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Set<String> capabilities() {
        return Set.copyOf(resolvedEndpoints.keySet());
    }

    private ResolvedEndpoint resolveFromRegistry(String capabilityTag, String tenancyId) {
        return registry.resolve(Path.of("http", capabilityTag), tenancyId)
            .filter(d -> d.protocol() == EndpointProtocol.HTTP)
            .map(this::buildFromDescriptor)
            .orElse(null);
    }

    private ResolvedEndpoint buildFromDescriptor(EndpointDescriptor descriptor) {
        Map<String, String> props = descriptor.properties();
        String url = props.get(EndpointPropertyKeys.URL);
        if (url == null || url.isBlank()) {
            throw new WorkerProvisioningException(
                "EndpointRegistry descriptor for " + descriptor.path().value()
                + " has blank URL");
        }
        String method = props.getOrDefault("method", "POST");
        ExchangeMode mode = parseMode(props.getOrDefault("mode", "SYNC"));
        int timeout = parseTimeout(props.get("timeout-seconds"), defaultTimeoutSeconds);
        Map<String, String> headers = extractHeaders(props);
        return new ResolvedEndpoint(url, method, mode, headers, timeout);
    }

    private ResolvedEndpoint buildFromConfig(Map<String, String> props, int defaultTimeout) {
        String url = props.get("url");
        String method = props.getOrDefault("method", "POST");
        ExchangeMode mode = parseMode(props.getOrDefault("mode", "SYNC"));
        int timeout = parseTimeout(props.get("timeout-seconds"), defaultTimeout);
        Map<String, String> headers = extractHeaders(props);
        return new ResolvedEndpoint(url, method, mode, headers, timeout);
    }

    private ExchangeMode parseMode(String value) {
        try {
            return ExchangeMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ExchangeMode.SYNC;
        }
    }

    private int parseTimeout(String value, int defaultTimeout) {
        if (value == null || value.isBlank()) {
            return defaultTimeout;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultTimeout;
        }
    }

    private Map<String, String> extractHeaders(Map<String, String> props) {
        Map<String, String> headers = new LinkedHashMap<>();
        String headerPrefix = "headers.";
        props.forEach((key, value) -> {
            if (key.startsWith(headerPrefix)) {
                headers.put(key.substring(headerPrefix.length()), value);
            }
        });
        return headers.isEmpty() ? Map.of() : Map.copyOf(headers);
    }

    /**
     * Loads config endpoints from MicroProfile Config by iterating keys
     * with prefix {@code casehub.workers.http.endpoints.} and grouping by tag.
     *
     * <p>Expected format:
     * <pre>
     * casehub.workers.http.endpoints.send-email.url=https://...
     * casehub.workers.http.endpoints.send-email.method=POST
     * casehub.workers.http.endpoints.send-email.mode=SYNC
     * casehub.workers.http.endpoints.send-email.timeout-seconds=30
     * casehub.workers.http.endpoints.send-email.headers.Authorization=Bearer xxx
     * </pre>
     */
    private Map<String, Map<String, String>> loadConfigEndpoints() {
        if (config == null) {
            return Map.of();
        }
        String prefix = "casehub.workers.http.endpoints.";
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix)) {
                String remainder = key.substring(prefix.length());
                int dot = remainder.indexOf('.');
                if (dot > 0) {
                    String tag = remainder.substring(0, dot);
                    String prop = remainder.substring(dot + 1);
                    config.getOptionalValue(key, String.class).ifPresent(value ->
                        result.computeIfAbsent(tag, k -> new LinkedHashMap<>()).put(prop, value)
                    );
                }
            }
        }
        return result;
    }
}
