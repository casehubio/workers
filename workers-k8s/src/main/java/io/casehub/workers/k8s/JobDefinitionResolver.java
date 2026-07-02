package io.casehub.workers.k8s;

import io.casehub.workers.common.WorkerCapabilityResolver;
import io.casehub.workers.common.WorkerProvisioningException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class JobDefinitionResolver implements WorkerCapabilityResolver<JobDefinition> {

    @Inject
    Config config;

    @ConfigProperty(name = "casehub.workers.k8s.namespace", defaultValue = "default")
    String defaultNamespace;

    @ConfigProperty(name = "casehub.workers.k8s.timeout-seconds", defaultValue = "3600")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "casehub.workers.k8s.ttl-after-finished", defaultValue = "600")
    int defaultTtlAfterFinished;

    @ConfigProperty(name = "casehub.workers.k8s.backoff-limit", defaultValue = "0")
    int defaultBackoffLimit;

    @ConfigProperty(name = "casehub.workers.k8s.cleanup", defaultValue = "delete")
    String defaultCleanup;

    @ConfigProperty(name = "casehub.workers.k8s.max-output-bytes", defaultValue = "1048576")
    long defaultMaxOutputBytes;

    @ConfigProperty(name = "casehub.workers.k8s.max-input-bytes", defaultValue = "262144")
    long defaultMaxInputBytes;

    private volatile Map<String, JobDefinition> definitions = Map.of();

    void initialize() {
        Map<String, Map<String, String>> configJobs = loadConfigJobs();
        Map<String, JobDefinition> parsed = new LinkedHashMap<>();
        configJobs.forEach((name, props) -> parsed.put(name, buildFromConfig(name, props)));
        initialize(parsed);
    }

    void initialize(Map<String, JobDefinition> jobDefinitions) {
        definitions = Map.copyOf(jobDefinitions);
    }

    @Override
    public JobDefinition resolve(String capabilityTag, String tenancyId) {
        if (!capabilityTag.startsWith(K8sWorkerConstants.TAG_PREFIX)) {
            throw WorkerProvisioningException.noRouteFound(capabilityTag);
        }
        String name = capabilityTag.substring(K8sWorkerConstants.TAG_PREFIX.length());
        JobDefinition def = definitions.get(name);
        if (def == null) {
            throw WorkerProvisioningException.noRouteFound(capabilityTag);
        }
        return def;
    }

    @Override
    public Optional<String> firstMatch(Set<String> capabilities, String tenancyId) {
        return capabilities.stream()
            .filter(cap -> {
                if (!cap.startsWith(K8sWorkerConstants.TAG_PREFIX)) return false;
                return definitions.containsKey(cap.substring(K8sWorkerConstants.TAG_PREFIX.length()));
            })
            .findFirst();
    }

    @Override
    public Set<String> capabilities() {
        return Set.copyOf(definitions.keySet().stream()
            .map(name -> K8sWorkerConstants.TAG_PREFIX + name)
            .toList());
    }

    public Set<String> namespaces() {
        return definitions.values().stream()
            .map(JobDefinition::namespace)
            .collect(Collectors.toUnmodifiableSet());
    }

    public long maxInputBytes() {
        return defaultMaxInputBytes;
    }

    private JobDefinition buildFromConfig(String name, Map<String, String> props) {
        String image = props.get("image");
        String template = props.get("template");
        if ((image == null || image.isBlank()) && (template == null || template.isBlank())) {
            throw new WorkerProvisioningException(
                "K8s job '" + name + "' must have either 'image' or 'template' configured");
        }
        String capTag = K8sWorkerConstants.TAG_PREFIX + name;
        if (capTag.length() > 63) {
            throw new WorkerProvisioningException(
                "Capability tag '" + capTag + "' exceeds K8s 63-char label value limit");
        }
        String namespace = propOrDefault(props, "namespace", defaultNamespace);
        int timeout = parseIntOrDefault(props.get("timeout-seconds"), defaultTimeoutSeconds);
        int ttl = Math.max(300, parseIntOrDefault(props.get("ttl-after-finished"), defaultTtlAfterFinished));
        int backoff = parseIntOrDefault(props.get("backoff-limit"), defaultBackoffLimit);
        long maxOutput = parseLongOrDefault(props.get("max-output-bytes"), defaultMaxOutputBytes);
        CleanupPolicy cleanup = parseCleanup(props.get("cleanup"), defaultCleanup);
        List<String> command = parseList(props.get("command"));
        List<String> args = parseList(props.get("args"));
        Map<String, String> env = extractPrefixed(props, "environment.");
        Map<String, String> labels = extractPrefixed(props, "labels.");

        return new JobDefinition(name, namespace, image, command, args, template,
            props.get("cpu-request"), props.get("cpu-limit"),
            props.get("memory-request"), props.get("memory-limit"),
            timeout, ttl, backoff, maxOutput,
            props.get("service-account"), labels, env, cleanup);
    }

    private Map<String, Map<String, String>> loadConfigJobs() {
        if (config == null) return Map.of();
        String prefix = "casehub.workers.k8s.jobs.";
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix)) {
                String remainder = key.substring(prefix.length());
                int dot = remainder.indexOf('.');
                if (dot > 0) {
                    String name = remainder.substring(0, dot);
                    String prop = remainder.substring(dot + 1);
                    config.getOptionalValue(key, String.class).ifPresent(value ->
                        result.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(prop, value));
                }
            }
        }
        return result;
    }

    private static String propOrDefault(Map<String, String> props, String key, String defaultValue) {
        String v = props.get(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split(","));
    }

    private static Map<String, String> extractPrefixed(Map<String, String> props, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        props.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                result.put(key.substring(prefix.length()), value);
            }
        });
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private static CleanupPolicy parseCleanup(String value, String defaultValue) {
        String v = (value != null && !value.isBlank()) ? value : defaultValue;
        return CleanupPolicy.valueOf(v.toUpperCase());
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static long parseLongOrDefault(String value, long defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
