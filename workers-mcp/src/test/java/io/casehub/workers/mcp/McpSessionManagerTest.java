package io.casehub.workers.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.workers.common.PermanentFaultException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class McpSessionManagerTest {

    private McpSessionManager manager;
    private McpServerResolver serverResolver;
    private WebClient webClient;
    private HttpRequest<Buffer> request;

    private static final ResolvedMcpServer TEST_SERVER = new ResolvedMcpServer(
        "slack", "https://slack.internal/mcp", 30,
        Map.of("Authorization", "Bearer xxx"), Set.of("send-message"));

    @BeforeEach
    void setUp() {
        serverResolver = mock(McpServerResolver.class);
        webClient = mock(WebClient.class);
        request = mock(HttpRequest.class);

        manager = new McpSessionManager();
        manager.serverResolver = serverResolver;
        manager.webClient = webClient;

        when(serverResolver.serverByName("slack")).thenReturn(TEST_SERVER);
        when(webClient.requestAbs(any(HttpMethod.class), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        when(request.putHeader(anyString(), anyString())).thenReturn(request);
    }

    @Test
    void firstDispatch_triggersInitialization() {
        // Mock initialize response (200 + JSON) with Mcp-Session-Id header
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        when(initResponse.getHeader("Mcp-Session-Id")).thenReturn("session-abc-123");

        // Mock initialized notification response (202)
        HttpResponse<Buffer> initializedResponse = mockResponse(202);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        McpSession session = manager.getOrInitialize("slack").await().indefinitely();

        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isEqualTo("session-abc-123");
        assertThat(session.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(session.hasSessionId()).isTrue();
    }

    @Test
    void secondDispatch_reusesCachedSession() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        when(initResponse.getHeader("Mcp-Session-Id")).thenReturn("session-abc-123");

        HttpResponse<Buffer> initializedResponse = mockResponse(202);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        McpSession s1 = manager.getOrInitialize("slack").await().indefinitely();
        McpSession s2 = manager.getOrInitialize("slack").await().indefinitely();

        assertThat(s1).isSameAs(s2);
        // Only 2 sendJson calls (init + initialized), not 4
        verify(request, times(2)).sendJson(any());
    }

    @Test
    void invalidate_nextDispatch_reinitializes() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        when(initResponse.getHeader("Mcp-Session-Id")).thenReturn("session-abc-123");

        HttpResponse<Buffer> initializedResponse = mockResponse(202);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        manager.getOrInitialize("slack").await().indefinitely();
        manager.invalidate("slack");
        manager.getOrInitialize("slack").await().indefinitely();

        // 4 total: 2 per init cycle (init + initialized)
        verify(request, times(4)).sendJson(any());
    }

    @Test
    void versionMismatch_permanentFault() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"OldServer\",\"version\":\"1.0\"}}}");

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse));

        assertThatThrownBy(() -> manager.getOrInitialize("slack").await().indefinitely())
            .isInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("2024-11-05");
    }

    @Test
    void transientFailure_removesCache_nextDispatchRetries() {
        // First call fails transiently
        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Connection refused")));

        assertThatThrownBy(() -> manager.getOrInitialize("slack").await().indefinitely())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Connection refused");

        // Reset mock — second call succeeds
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        when(initResponse.getHeader("Mcp-Session-Id")).thenReturn("session-retry");

        HttpResponse<Buffer> initializedResponse = mockResponse(202);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        McpSession session = manager.getOrInitialize("slack").await().indefinitely();

        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isEqualTo("session-retry");
    }

    @Test
    void noSessionId_sessionHasNullId() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        // No Mcp-Session-Id header — getHeader returns null (default mock behavior)

        HttpResponse<Buffer> initializedResponse = mockResponse(202);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        McpSession session = manager.getOrInitialize("slack").await().indefinitely();

        assertThat(session.hasSessionId()).isFalse();
    }

    @Test
    void requestIdCounter_startsAt2() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        when(initResponse.getHeader("Mcp-Session-Id")).thenReturn("session-abc");

        HttpResponse<Buffer> initializedResponse = mockResponse(202);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        McpSession session = manager.getOrInitialize("slack").await().indefinitely();

        assertThat(session.nextRequestId()).isEqualTo(2);
        assertThat(session.nextRequestId()).isEqualTo(3);
    }

    @Test
    void http4xx_onInitialize_permanentFault() {
        HttpResponse<Buffer> response = mockResponse(401);
        when(response.statusMessage()).thenReturn("Unauthorized");

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(response));

        assertThatThrownBy(() -> manager.getOrInitialize("slack").await().indefinitely())
            .isInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("401");
    }

    @Test
    void http5xx_onInitialize_runtimeException() {
        HttpResponse<Buffer> response = mockResponse(503);
        when(response.statusMessage()).thenReturn("Service Unavailable");

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(response));

        assertThatThrownBy(() -> manager.getOrInitialize("slack").await().indefinitely())
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("503");
    }

    @Test
    void jsonRpcError_inResponse_permanentFault() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600,\"message\":\"Invalid request\"}}");

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse));

        assertThatThrownBy(() -> manager.getOrInitialize("slack").await().indefinitely())
            .isInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("Invalid request");
    }

    @Test
    void malformedJson_inResponse_permanentFault() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200, "not json at all");

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse));

        assertThatThrownBy(() -> manager.getOrInitialize("slack").await().indefinitely())
            .isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void initializedNotification_non202_logsWarningButReturnsSession() {
        HttpResponse<Buffer> initResponse = mockJsonResponse(200,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"TestServer\",\"version\":\"1.0\"}}}");
        when(initResponse.getHeader("Mcp-Session-Id")).thenReturn("session-abc");

        // initialized responds with 200 instead of 202 — not fatal
        HttpResponse<Buffer> initializedResponse = mockResponse(200);

        when(request.sendJson(any()))
            .thenReturn(Uni.createFrom().item(initResponse))
            .thenReturn(Uni.createFrom().item(initializedResponse));

        McpSession session = manager.getOrInitialize("slack").await().indefinitely();

        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isEqualTo("session-abc");
    }

    // --- Helpers ---

    private HttpResponse<Buffer> mockResponse(int status) {
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.statusMessage()).thenReturn("Status " + status);
        when(response.getHeader(anyString())).thenReturn(null);
        return response;
    }

    private HttpResponse<Buffer> mockJsonResponse(int status, String body) {
        HttpResponse<Buffer> response = mockResponse(status);
        when(response.bodyAsString()).thenReturn(body);
        when(response.body()).thenReturn(Buffer.buffer(body));
        return response;
    }
}
