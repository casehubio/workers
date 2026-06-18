package io.casehub.workers.script;

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

@ApplicationScoped
public class ScriptDefinitionResolver implements WorkerCapabilityResolver<ScriptDefinition> {

    private static final String TAG_PREFIX = "script:";

    @Inject
    Config config;

    @ConfigProperty(name = "casehub.workers.script.default-timeout-seconds", defaultValue = "300")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "casehub.workers.script.max-output-bytes", defaultValue = "1048576")
    long defaultMaxOutputBytes;

    private volatile Map<String, ScriptDefinition> definitions = Map.of();

    void initialize() {
        Map<String, Map<String, String>> configScripts = loadConfigScripts();
        Map<String, ScriptDefinition> parsed = new LinkedHashMap<>();
        configScripts.forEach((name, props) -> {
            parsed.put(name, buildFromConfig(name, props));
        });
        initialize(parsed);
    }

    void initialize(Map<String, ScriptDefinition> scriptDefinitions) {
        definitions = Map.copyOf(scriptDefinitions);
    }

    @Override
    public ScriptDefinition resolve(String capabilityTag, String tenancyId) {
        if (!capabilityTag.startsWith(TAG_PREFIX)) {
            throw WorkerProvisioningException.noRouteFound(capabilityTag);
        }
        String name = capabilityTag.substring(TAG_PREFIX.length());
        ScriptDefinition def = definitions.get(name);
        if (def == null) {
            throw WorkerProvisioningException.noRouteFound(capabilityTag);
        }
        return def;
    }

    @Override
    public Optional<String> firstMatch(Set<String> capabilities, String tenancyId) {
        return capabilities.stream()
            .filter(cap -> {
                if (!cap.startsWith(TAG_PREFIX)) return false;
                return definitions.containsKey(cap.substring(TAG_PREFIX.length()));
            })
            .findFirst();
    }

    @Override
    public Set<String> capabilities() {
        return Set.copyOf(definitions.keySet().stream()
            .map(name -> TAG_PREFIX + name)
            .toList());
    }

    private ScriptDefinition buildFromConfig(String name, Map<String, String> props) {
        String command = props.get("command");
        if (command == null || command.isBlank()) {
            throw new WorkerProvisioningException(
                "Script '" + name + "' has no 'command' configured");
        }
        List<String> args = parseArgs(props.get("args"));
        String workingDirectory = props.get("working-directory");
        Map<String, String> env = extractEnvironment(props);
        int timeout = parseIntOrDefault(props.get("timeout-seconds"), defaultTimeoutSeconds);
        long maxOutput = parseLongOrDefault(props.get("max-output-bytes"), defaultMaxOutputBytes);
        return new ScriptDefinition(name, command, args, workingDirectory, env, timeout, maxOutput);
    }

    private List<String> parseArgs(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split(","));
    }

    private Map<String, String> extractEnvironment(Map<String, String> props) {
        Map<String, String> env = new LinkedHashMap<>();
        String prefix = "environment.";
        props.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                env.put(key.substring(prefix.length()), value);
            }
        });
        return env.isEmpty() ? Map.of() : Map.copyOf(env);
    }

    private Map<String, Map<String, String>> loadConfigScripts() {
        if (config == null) return Map.of();
        String prefix = "casehub.workers.script.scripts.";
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix)) {
                String remainder = key.substring(prefix.length());
                int dot = remainder.indexOf('.');
                if (dot > 0) {
                    String name = remainder.substring(0, dot);
                    String prop = remainder.substring(dot + 1);
                    config.getOptionalValue(key, String.class).ifPresent(value ->
                        result.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(prop, value)
                    );
                }
            }
        }
        return result;
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
