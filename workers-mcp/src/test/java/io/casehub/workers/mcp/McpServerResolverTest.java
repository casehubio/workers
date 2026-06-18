package io.casehub.workers.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;
import io.casehub.workers.common.WorkerProvisioningException;
import io.casehub.workers.mcp.McpServerResolver.ServerConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpServerResolverTest {

    private static final String TENANT_1 = "tenant-1";

    // --- Tier 3 test helpers ---

    private static EndpointRegistry stubMcpRegistry(String serverName, EndpointDescriptor descriptor) {
        return new EndpointRegistry() {
            @Override public void register(EndpointDescriptor endpoint) {}
            @Override public Optional<EndpointDescriptor> resolve(Path path, String tenancyId) {
                if (path.equals(Path.of("mcp", serverName))) return Optional.of(descriptor);
                return Optional.empty();
            }
            @Override public List<EndpointDescriptor> discover(EndpointQuery query) { return List.of(); }
            @Override public void deregister(Path path, String tenancyId) {}
        };
    }

    private static EndpointRegistry emptyRegistry() {
        return new EndpointRegistry() {
            @Override public void register(EndpointDescriptor endpoint) {}
            @Override public Optional<EndpointDescriptor> resolve(Path path, String tenancyId) { return Optional.empty(); }
            @Override public List<EndpointDescriptor> discover(EndpointQuery query) { return List.of(); }
            @Override public void deregister(Path path, String tenancyId) {}
        };
    }

    private static EndpointDescriptor mcpDescriptor(String serverName, Map<String, String> props) {
        return new EndpointDescriptor(
            Path.of("mcp", serverName),
            TENANT_1,
            EndpointType.WORKER,
            EndpointProtocol.MCP,
            props,
            null,
            Set.of(EndpointCapability.DISPATCH)
        );
    }

    // --- Existing tests (updated with tenancyId) ---

    @Test
    void singleServerWithTwoTools_buildsTwoCapabilities() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,list-channels", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message",
            "mcp:slack:list-channels"
        );
    }

    @Test
    void multipleServers_combinedCapabilities() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto"),
            new ServerConfig("jira", "https://jira.internal/mcp", "create-issue,search", 60, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message",
            "mcp:jira:create-issue",
            "mcp:jira:search"
        );
    }

    @Test
    void resolveWithValidTag_returnsCorrectServer() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of("Authorization", "Bearer xxx"), "auto")
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);
        assertThat(resolved.name()).isEqualTo("slack");
        assertThat(resolved.url()).isEqualTo("https://slack.internal/mcp");
        assertThat(resolved.timeoutSeconds()).isEqualTo(30);
        assertThat(resolved.headers()).containsEntry("Authorization", "Bearer xxx");
        assertThat(resolved.tools()).contains("send-message");
    }

    @Test
    void multipleTagsToSameServer_returnsSameInstance() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,list-channels", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer first = resolver.resolve("mcp:slack:send-message", TENANT_1);
        ResolvedMcpServer second = resolver.resolve("mcp:slack:list-channels", TENANT_1);
        assertThat(first).isSameAs(second);
    }

    @Test
    void resolveWithUnknownServer_throws() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThatThrownBy(() -> resolver.resolve("mcp:unknown:send-message", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("No route found");
    }

    @Test
    void resolveWithKnownServerButUnlistedTool_throws() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThatThrownBy(() -> resolver.resolve("mcp:slack:delete-message", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("No route found");
    }

    @Test
    void timeoutFallsBackToGlobalDefault() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", -1, Map.of(), "auto")
        );

        resolver.initialize(servers, 45);

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);
        assertThat(resolved.timeoutSeconds()).isEqualTo(45);
    }

    @Test
    void noHeaders_emptyMap() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);
        assertThat(resolved.headers()).isEmpty();
    }

    @Test
    void blankUrl_throwsAtStartup() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "", "send-message", 30, Map.of(), "auto")
        );

        assertThatThrownBy(() -> resolver.initialize(servers, 30))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("URL");
    }

    @Test
    void toolsParsing_trimsWhitespace() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", " send-message , list-channels ", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message",
            "mcp:slack:list-channels"
        );
    }

    @Test
    void toolsParsing_ignoresEmptyEntries() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,,list-channels,", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message",
            "mcp:slack:list-channels"
        );
    }

    @Test
    void toolsParsing_duplicateToolNames_throws() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,send-message", 30, Map.of(), "auto")
        );

        assertThatThrownBy(() -> resolver.initialize(servers, 30))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("duplicate")
            .hasMessageContaining("send-message");
    }

    @Test
    void emptyTools_noCapabilities() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThat(resolver.capabilities()).isEmpty();
    }

    @Test
    void firstMatch_findsMatch() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        Optional<String> match = resolver.firstMatch(Set.of("mcp:slack:send-message", "http:send-email"), TENANT_1);
        assertThat(match).hasValue("mcp:slack:send-message");
    }

    @Test
    void firstMatch_noMatch_returnsEmpty() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        Optional<String> match = resolver.firstMatch(Set.of("http:send-email", "camel:process"), TENANT_1);
        assertThat(match).isEmpty();
    }

    @Test
    void parseServerName_extractsServerFromTag() {
        assertThat(McpServerResolver.parseServerName("mcp:slack:send-message")).isEqualTo("slack");
    }

    @Test
    void parseToolName_extractsToolFromTag() {
        assertThat(McpServerResolver.parseToolName("mcp:slack:send-message")).isEqualTo("send-message");
    }

    @Test
    void serverByName_returnsCorrectServer() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer server = resolver.serverByName("slack");
        assertThat(server.name()).isEqualTo("slack");
        assertThat(server.url()).isEqualTo("https://slack.internal/mcp");
    }

    @Test
    void serverByName_unknownServer_throws() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );

        resolver.initialize(servers, 30);

        assertThatThrownBy(() -> resolver.serverByName("jira"))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("jira");
    }

    @Test
    void registerDiscoveredTools_fullDiscovery_registersAllTools() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30);

        resolver.registerDiscoveredTools("slack", Set.of("send-message", "list-channels"));

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message",
            "mcp:slack:list-channels"
        );
    }

    @Test
    void registerDiscoveredTools_withAllowlist_registersOnlyConfigTools() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30);

        resolver.registerDiscoveredTools("slack", Set.of("send-message", "list-channels", "delete-message"));

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message"
        );
    }

    @Test
    void registerDiscoveredTools_allowlistToolNotInDiscovery_keptWithWarning() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,custom-tool", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30);

        resolver.registerDiscoveredTools("slack", Set.of("send-message"));

        assertThat(resolver.capabilities()).containsExactlyInAnyOrder(
            "mcp:slack:send-message",
            "mcp:slack:custom-tool"
        );
    }

    @Test
    void registerDiscoveredTools_rebuildsTags() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30);
        assertThat(resolver.capabilities()).isEmpty();

        resolver.registerDiscoveredTools("slack", Set.of("send-message"));

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);
        assertThat(resolved.name()).isEqualTo("slack");
        assertThat(resolved.tools()).contains("send-message");
    }

    @Test
    void isDiscoveryEnabled_autoMode_returnsTrue() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30);
        assertThat(resolver.isDiscoveryEnabled("slack")).isTrue();
    }

    @Test
    void isDiscoveryEnabled_manualMode_returnsFalse() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "manual")
        );
        resolver.initialize(servers, 30);
        assertThat(resolver.isDiscoveryEnabled("slack")).isFalse();
    }

    @Test
    void isDiscoveryEnabled_defaultMode_returnsTrue() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "", 30, Map.of(), "")
        );
        resolver.initialize(servers, 30);
        assertThat(resolver.isDiscoveryEnabled("slack")).isTrue();
    }

    // --- Tier 3 EndpointRegistry tests ---

    @Test
    void tier3_registryHit_resolvesServerFromRegistry() {
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            EndpointPropertyKeys.URL, "https://slack.internal/mcp",
            "timeout-seconds", "60",
            "tools", "send-message,list-channels",
            "headers.Authorization", "Bearer token"
        ));
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", descriptor));

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);

        assertThat(resolved.name()).isEqualTo("slack");
        assertThat(resolved.url()).isEqualTo("https://slack.internal/mcp");
        assertThat(resolved.timeoutSeconds()).isEqualTo(60);
        assertThat(resolved.headers()).containsEntry("Authorization", "Bearer token");
        assertThat(resolved.tools()).containsExactlyInAnyOrder("send-message", "list-channels");
    }

    @Test
    void tier3_registryMiss_throws() {
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, emptyRegistry());

        assertThatThrownBy(() -> resolver.resolve("mcp:unknown:some-tool", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("No route found");
    }

    @Test
    void tier1_winsOverTier3() {
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            EndpointPropertyKeys.URL, "https://registry.example.com/slack-mcp"
        ));
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://config.internal/mcp", "send-message", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30, stubMcpRegistry("slack", descriptor));

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);

        // Config URL wins, not registry URL
        assertThat(resolved.url()).isEqualTo("https://config.internal/mcp");
    }

    @Test
    void tier3_wrongProtocol_ignored() {
        // Registry returns an HTTP descriptor at an MCP path — protocol mismatch
        EndpointDescriptor httpDescriptor = new EndpointDescriptor(
            Path.of("mcp", "slack"),
            TENANT_1,
            EndpointType.WORKER,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, "https://http.example.com/slack"),
            null,
            Set.of(EndpointCapability.DISPATCH)
        );
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", httpDescriptor));

        assertThatThrownBy(() -> resolver.resolve("mcp:slack:send-message", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("No route found");
    }

    @Test
    void tier3_emptyTools_discoveryMode() {
        // Descriptor with no tools property → server has empty tools set (discovery mode)
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            EndpointPropertyKeys.URL, "https://slack.internal/mcp"
        ));
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", descriptor));

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:any-tool", TENANT_1);

        assertThat(resolved.name()).isEqualTo("slack");
        assertThat(resolved.url()).isEqualTo("https://slack.internal/mcp");
        assertThat(resolved.tools()).isEmpty();
    }

    @Test
    void firstMatch_serverExistenceIsMatch() {
        // Registry has server "slack" — mcp:slack:any-tool matches (server existence = match)
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            EndpointPropertyKeys.URL, "https://slack.internal/mcp"
        ));
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", descriptor));

        Optional<String> match = resolver.firstMatch(
            Set.of("mcp:slack:any-tool", "http:send-email"), TENANT_1);

        assertThat(match).hasValue("mcp:slack:any-tool");
    }

    @Test
    void tier3_blankUrl_throws() {
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            EndpointPropertyKeys.URL, "   "
        ));
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", descriptor));

        assertThatThrownBy(() -> resolver.resolve("mcp:slack:send-message", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("blank URL");
    }

    @Test
    void tier3_missingUrl_throws() {
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            "timeout-seconds", "30"
        ));
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", descriptor));

        assertThatThrownBy(() -> resolver.resolve("mcp:slack:send-message", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("blank URL");
    }

    @Test
    void tier3_timeoutFallsBackToDefault() {
        // Descriptor without timeout-seconds — should use defaultTimeoutSeconds
        EndpointDescriptor descriptor = mcpDescriptor("slack", Map.of(
            EndpointPropertyKeys.URL, "https://slack.internal/mcp"
        ));
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 45, stubMcpRegistry("slack", descriptor));

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message", TENANT_1);
        assertThat(resolved.timeoutSeconds()).isEqualTo(45);
    }

    @Test
    void nullRegistry_resolveFallsThrough() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of(), "auto")
        );
        resolver.initialize(servers, 30); // 2-arg overload passes null registry

        // Config still works
        assertThat(resolver.resolve("mcp:slack:send-message", TENANT_1).url())
            .isEqualTo("https://slack.internal/mcp");

        // Unknown tag throws (no registry to fall back to)
        assertThatThrownBy(() -> resolver.resolve("mcp:unknown:tool", TENANT_1))
            .isInstanceOf(WorkerProvisioningException.class);
    }

    @Test
    void firstMatch_registryWrongProtocol_notMatched() {
        EndpointDescriptor httpDescriptor = new EndpointDescriptor(
            Path.of("mcp", "slack"),
            TENANT_1,
            EndpointType.WORKER,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, "https://http.example.com/slack"),
            null,
            Set.of(EndpointCapability.DISPATCH)
        );
        McpServerResolver resolver = new McpServerResolver();
        resolver.initialize(List.of(), 30, stubMcpRegistry("slack", httpDescriptor));

        assertThat(resolver.firstMatch(Set.of("mcp:slack:any-tool"), TENANT_1)).isEmpty();
    }
}
