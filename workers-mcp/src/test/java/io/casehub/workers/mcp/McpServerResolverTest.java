package io.casehub.workers.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.workers.common.WorkerProvisioningException;
import io.casehub.workers.mcp.McpServerResolver.ServerConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpServerResolverTest {

    @Test
    void singleServerWithTwoTools_buildsTwoCapabilities() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,list-channels", 30, Map.of())
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of()),
            new ServerConfig("jira", "https://jira.internal/mcp", "create-issue,search", 60, Map.of())
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of("Authorization", "Bearer xxx"))
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message");
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,list-channels", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer first = resolver.resolve("mcp:slack:send-message");
        ResolvedMcpServer second = resolver.resolve("mcp:slack:list-channels");
        assertThat(first).isSameAs(second);
    }

    @Test
    void resolveWithUnknownServer_throws() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        assertThatThrownBy(() -> resolver.resolve("mcp:unknown:send-message"))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("No route found");
    }

    @Test
    void resolveWithKnownServerButUnlistedTool_throws() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        assertThatThrownBy(() -> resolver.resolve("mcp:slack:delete-message"))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("No route found");
    }

    @Test
    void timeoutFallsBackToGlobalDefault() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", -1, Map.of())
        );

        resolver.initialize(servers, 45);

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message");
        assertThat(resolved.timeoutSeconds()).isEqualTo(45);
    }

    @Test
    void noHeaders_emptyMap() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        ResolvedMcpServer resolved = resolver.resolve("mcp:slack:send-message");
        assertThat(resolved.headers()).isEmpty();
    }

    @Test
    void blankUrl_throwsAtStartup() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "", "send-message", 30, Map.of())
        );

        assertThatThrownBy(() -> resolver.initialize(servers, 30))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("URL");
    }

    @Test
    void toolsParsing_trimsWhitespace() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", " send-message , list-channels ", 30, Map.of())
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,,list-channels,", 30, Map.of())
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message,send-message", 30, Map.of())
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
            new ServerConfig("slack", "https://slack.internal/mcp", "", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        assertThat(resolver.capabilities()).isEmpty();
    }

    @Test
    void firstMatch_findsMatch() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        Optional<String> match = resolver.firstMatch(Set.of("mcp:slack:send-message", "http:send-email"));
        assertThat(match).hasValue("mcp:slack:send-message");
    }

    @Test
    void firstMatch_noMatch_returnsEmpty() {
        McpServerResolver resolver = new McpServerResolver();
        List<ServerConfig> servers = List.of(
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        Optional<String> match = resolver.firstMatch(Set.of("http:send-email", "camel:process"));
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
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
            new ServerConfig("slack", "https://slack.internal/mcp", "send-message", 30, Map.of())
        );

        resolver.initialize(servers, 30);

        assertThatThrownBy(() -> resolver.serverByName("jira"))
            .isInstanceOf(WorkerProvisioningException.class)
            .hasMessageContaining("jira");
    }
}
