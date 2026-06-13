package io.casehub.workers.mcp;

import io.casehub.workers.common.WorkerCapabilityResolver;
import io.casehub.workers.common.WorkerProvisioningException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class McpServerResolver implements WorkerCapabilityResolver<ResolvedMcpServer> {

    private static final Logger LOG = Logger.getLogger(McpServerResolver.class);

    record ServerConfig(String name, String url, String tools, int timeoutSeconds, Map<String, String> headers, String discovery) {}

    @Inject
    Config config;

    @ConfigProperty(name = "casehub.workers.mcp.default-timeout-seconds", defaultValue = "30")
    int defaultTimeoutSeconds;

    private final Map<String, ResolvedMcpServer> serversByName = new HashMap<>();
    private final Map<String, String> capabilityToServerName = new HashMap<>();
    private final Map<String, ServerConfig> configByName = new HashMap<>();

    /**
     * Loads server configuration from MicroProfile Config and initializes
     * the resolver. Called by {@code McpWorkerRuntime.initialize()} instead
     * of a CDI startup observer — this allows the runtime to control
     * initialization order.
     */
    void initializeFromConfig() {
        List<ServerConfig> servers = loadFromConfig();
        initialize(servers, defaultTimeoutSeconds);
    }

    /**
     * Test-friendly initializer. Accepts all inputs explicitly so tests
     * can call without CDI.
     */
    void initialize(List<ServerConfig> servers, int defaultTimeout) {
        serversByName.clear();
        capabilityToServerName.clear();
        configByName.clear();

        for (ServerConfig config : servers) {
            validateServerConfig(config);
            configByName.put(config.name(), config);

            int timeout = config.timeoutSeconds() == -1 ? defaultTimeout : config.timeoutSeconds();
            Set<String> tools = parseTools(config.tools(), config.name());

            ResolvedMcpServer server = new ResolvedMcpServer(
                config.name(),
                config.url(),
                timeout,
                config.headers() == null ? Map.of() : Map.copyOf(config.headers()),
                tools
            );

            serversByName.put(config.name(), server);

            // Register capability tags for each tool
            for (String tool : tools) {
                String capabilityTag = buildCapabilityTag(config.name(), tool);
                capabilityToServerName.put(capabilityTag, config.name());
            }
        }
    }

    @Override
    public ResolvedMcpServer resolve(String capabilityTag) {
        String serverName = capabilityToServerName.get(capabilityTag);
        if (serverName == null) {
            throw WorkerProvisioningException.noRouteFound(capabilityTag);
        }
        return serversByName.get(serverName);
    }

    @Override
    public Optional<String> firstMatch(Set<String> capabilities) {
        return capabilities.stream()
            .filter(capabilityToServerName::containsKey)
            .findFirst();
    }

    @Override
    public Set<String> capabilities() {
        return Set.copyOf(capabilityToServerName.keySet());
    }

    /**
     * Package-private access to server by name — used by McpSessionManager.
     */
    ResolvedMcpServer serverByName(String name) {
        ResolvedMcpServer server = serversByName.get(name);
        if (server == null) {
            throw new WorkerProvisioningException("No MCP server found with name: " + name);
        }
        return server;
    }

    /**
     * Returns whether discovery mode is enabled for the given server.
     * Returns true if discovery is null, blank, or "auto".
     * Returns false if discovery is "manual".
     */
    boolean isDiscoveryEnabled(String serverName) {
        ServerConfig config = configByName.get(serverName);
        if (config == null) return false;
        String mode = config.discovery();
        return mode == null || mode.isBlank() || "auto".equalsIgnoreCase(mode);
    }

    /**
     * Returns the list of configured server names.
     */
    List<String> serverNames() {
        return List.copyOf(serversByName.keySet());
    }

    /**
     * Registers discovered tools for the given server.
     * If no tools are configured, registers all discovered tools.
     * If tools are configured, only registers the configured subset (allowlist).
     * Warns if a configured tool is not in the discovery response.
     */
    void registerDiscoveredTools(String serverName, Set<String> discoveredToolNames) {
        ResolvedMcpServer existing = serversByName.get(serverName);
        if (existing == null) {
            throw new WorkerProvisioningException("No MCP server found with name: " + serverName);
        }

        ServerConfig config = configByName.get(serverName);
        Set<String> configTools = parseTools(config.tools(), serverName);
        Set<String> finalTools;

        if (configTools.isEmpty()) {
            finalTools = Set.copyOf(discoveredToolNames);
        } else {
            for (String configTool : configTools) {
                if (!discoveredToolNames.contains(configTool)) {
                    LOG.warnf("MCP server '%s': config-declared tool '%s' not found in tools/list response",
                        serverName, configTool);
                }
            }
            finalTools = configTools;
        }

        // Remove old tags for this server
        Set<String> oldTags = new HashSet<>();
        capabilityToServerName.forEach((tag, name) -> {
            if (name.equals(serverName)) oldTags.add(tag);
        });
        oldTags.forEach(capabilityToServerName::remove);

        // Replace server with updated tools
        ResolvedMcpServer updated = new ResolvedMcpServer(
            existing.name(), existing.url(), existing.timeoutSeconds(),
            existing.headers(), finalTools
        );
        serversByName.put(serverName, updated);

        // Rebuild tags
        for (String tool : finalTools) {
            capabilityToServerName.put(buildCapabilityTag(serverName, tool), serverName);
        }
    }

    /**
     * Static utility — extracts server name from capability tag.
     * Format: mcp:{server}:{tool}
     */
    public static String parseServerName(String capabilityTag) {
        String[] parts = capabilityTag.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    /**
     * Static utility — extracts tool name from capability tag.
     * Format: mcp:{server}:{tool}
     */
    public static String parseToolName(String capabilityTag) {
        String[] parts = capabilityTag.split(":", 3);
        return parts.length >= 3 ? parts[2] : "";
    }

    private String buildCapabilityTag(String serverName, String tool) {
        return "mcp:" + serverName + ":" + tool;
    }

    private void validateServerConfig(ServerConfig config) {
        if (config.url() == null || config.url().isBlank()) {
            throw new WorkerProvisioningException(
                "MCP server '" + config.name() + "' has blank URL — URL is required"
            );
        }
    }

    private Set<String> parseTools(String toolsStr, String serverName) {
        if (toolsStr == null || toolsStr.isBlank()) {
            return Set.of();
        }

        Set<String> tools = new HashSet<>();
        String[] parts = toolsStr.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (tools.contains(trimmed)) {
                    throw new WorkerProvisioningException(
                        "MCP server '" + serverName + "' has duplicate tool: " + trimmed
                    );
                }
                tools.add(trimmed);
            }
        }
        return Set.copyOf(tools);
    }

    /**
     * Loads config from MicroProfile Config by iterating keys with prefix
     * {@code casehub.workers.mcp.servers.} and grouping by server name.
     *
     * <p>Expected format:
     * <pre>
     * casehub.workers.mcp.servers.slack.url=https://slack.internal/mcp
     * casehub.workers.mcp.servers.slack.tools=send-message,list-channels
     * casehub.workers.mcp.servers.slack.timeout-seconds=30
     * casehub.workers.mcp.servers.slack.headers.Authorization=Bearer xxx
     * </pre>
     */
    private List<ServerConfig> loadFromConfig() {
        if (config == null) {
            return List.of();
        }

        String prefix = "casehub.workers.mcp.servers.";
        Map<String, Map<String, String>> serverProps = new LinkedHashMap<>();

        for (String key : config.getPropertyNames()) {
            if (key.startsWith(prefix)) {
                String remainder = key.substring(prefix.length());
                int dot = remainder.indexOf('.');
                if (dot > 0) {
                    String serverName = remainder.substring(0, dot);
                    String prop = remainder.substring(dot + 1);
                    config.getOptionalValue(key, String.class).ifPresent(value ->
                        serverProps.computeIfAbsent(serverName, k -> new LinkedHashMap<>()).put(prop, value)
                    );
                }
            }
        }

        return serverProps.entrySet().stream()
            .map(entry -> buildServerConfig(entry.getKey(), entry.getValue()))
            .toList();
    }

    private ServerConfig buildServerConfig(String name, Map<String, String> props) {
        String url = props.get("url");
        String tools = props.getOrDefault("tools", "");
        int timeout = parseTimeout(props.get("timeout-seconds"));
        Map<String, String> headers = extractHeaders(props);
        String discovery = props.getOrDefault("discovery", "auto");
        return new ServerConfig(name, url, tools, timeout, headers, discovery);
    }

    private int parseTimeout(String value) {
        if (value == null || value.isBlank()) {
            return -1; // Signal to use default
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
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
}
