package io.casehub.workers.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.RetryAfterException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkflowCompletionPublisher;
import io.casehub.workers.testing.WorkerTestSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class McpWorkerExecutionManagerTest {

    private McpWorkerExecutionManager manager;
    private McpServerResolver serverResolver;
    private McpSessionManager sessionManager;
    private WorkerFaultPublisher faultPublisher;
    private WorkflowCompletionPublisher completionPublisher;
    private WebClient webClient;
    private HttpRequest<Buffer> request;

    private static final String CAP_TAG = "mcp:slack:send-message";
    private static final ResolvedMcpServer TEST_SERVER = new ResolvedMcpServer(
        "slack",
        "https://slack.internal/mcp",
        30,
        Map.of("Authorization", "Bearer test-token"),
        java.util.Set.of("send-message", "list-channels")
    );

    @BeforeEach
    void setUp() {
        serverResolver = mock(McpServerResolver.class);
        sessionManager = mock(McpSessionManager.class);
        faultPublisher = mock(WorkerFaultPublisher.class);
        completionPublisher = mock(WorkflowCompletionPublisher.class);
        webClient = mock(WebClient.class);
        request = mock(HttpRequest.class);

        manager = new McpWorkerExecutionManager();
        manager.serverResolver = serverResolver;
        manager.sessionManager = sessionManager;
        manager.faultPublisher = faultPublisher;
        manager.completionPublisher = completionPublisher;
        manager.webClient = webClient;

        when(serverResolver.resolve(eq(CAP_TAG), anyString())).thenReturn(TEST_SERVER);
        when(sessionManager.getOrInitialize("slack"))
            .thenReturn(Uni.createFrom().item(new McpSession("session-123", "2025-06-18")));
        when(webClient.requestAbs(any(HttpMethod.class), anyString())).thenReturn(request);
        when(request.putHeader(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
    }

    @Test
    void successfulToolCall_json_completesWithContent() {
        String jsonRpcResponse = """
            {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Message sent"}]}}""";
        HttpResponse<Buffer> response = mockJsonResponse(200, jsonRpcResponse);
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general", "text", "hello"))
            .await().indefinitely();

        ArgumentCaptor<Map<String, Object>> outputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completionPublisher).complete(any(WorkerCorrelationContext.class), outputCaptor.capture());
        assertThat(outputCaptor.getValue()).containsKey("content");
        verify(faultPublisher, never()).fault(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    void successfulToolCall_structuredContent_preferred() {
        String jsonRpcResponse = """
            {"jsonrpc":"2.0","id":2,"result":{
              "content":[{"type":"text","text":"ignored"}],
              "structuredContent":{"status":"ok","messageId":"m-123"}
            }}""";
        HttpResponse<Buffer> response = mockJsonResponse(200, jsonRpcResponse);
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Map<String, Object>> outputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completionPublisher).complete(any(WorkerCorrelationContext.class), outputCaptor.capture());
        assertThat(outputCaptor.getValue())
            .containsEntry("status", "ok")
            .containsEntry("messageId", "m-123")
            .doesNotContainKey("content");
        verify(faultPublisher, never()).fault(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    void isError_true_retryableFault() {
        String jsonRpcResponse = """
            {"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"Tool failed"}]}}""";
        HttpResponse<Buffer> response = mockJsonResponse(200, jsonRpcResponse);
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue())
            .isNotInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("isError");
        verify(completionPublisher, never()).complete(any(), any());
    }

    @Test
    void jsonRpcError_invalidParams_permanentFault() {
        String jsonRpcResponse = """
            {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Invalid params"}}""";
        HttpResponse<Buffer> response = mockJsonResponse(200, jsonRpcResponse);
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void jsonRpcError_internalError_retryable() {
        String jsonRpcResponse = """
            {"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"Internal error"}}""";
        HttpResponse<Buffer> response = mockJsonResponse(200, jsonRpcResponse);
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue())
            .isNotInstanceOf(PermanentFaultException.class)
            .isNotInstanceOf(RetryAfterException.class);
    }

    @Test
    void http404_withSession_retryable() {
        HttpResponse<Buffer> response = mockResponse(404);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        verify(sessionManager).invalidate("slack");
        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isNotInstanceOf(PermanentFaultException.class);
    }

    @Test
    void http404_withoutSession_permanentFault() {
        // Session with null sessionId — no active session
        when(sessionManager.getOrInitialize("slack"))
            .thenReturn(Uni.createFrom().item(new McpSession(null, "2025-06-18")));
        HttpResponse<Buffer> response = mockResponse(404);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        verify(sessionManager, never()).invalidate(anyString());
        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void http429_retryAfter() {
        HttpResponse<Buffer> response = mockResponse(429);
        when(response.getHeader("Retry-After")).thenReturn("60");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(RetryAfterException.class);
        assertThat(((RetryAfterException) causeCaptor.getValue()).retryAfterMs()).isEqualTo(60000L);
    }

    @Test
    void http400_permanentFault() {
        HttpResponse<Buffer> response = mockResponse(400);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
        assertThat(((PermanentFaultException) causeCaptor.getValue()).statusCode()).isEqualTo(400);
    }

    @Test
    void http500_retryable() {
        HttpResponse<Buffer> response = mockResponse(500);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue())
            .isNotInstanceOf(PermanentFaultException.class)
            .isNotInstanceOf(RetryAfterException.class);
    }

    @Test
    void malformedResponse_retryable() {
        HttpResponse<Buffer> response = mockJsonResponse(200, "<html>Bad Gateway</html>");
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(McpWorkerEventBusAddresses.MCP_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue())
            .isNotInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("Malformed");
    }

    @Test
    void protocolHeaders_sentCorrectly() {
        String jsonRpcResponse = """
            {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ok"}]}}""";
        HttpResponse<Buffer> response = mockJsonResponse(200, jsonRpcResponse);
        when(response.getHeader("Content-Type")).thenReturn("application/json");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        verify(request).putHeader("Content-Type", "application/json");
        verify(request).putHeader("Accept", "application/json, text/event-stream");
        verify(request).putHeader("MCP-Protocol-Version", "2025-06-18");
        verify(request).putHeader("Mcp-Session-Id", "session-123");
        verify(request).putHeader("Authorization", "Bearer test-token");
        verify(request).timeout(30000L);
    }

    @Test
    void sseResponse_extractsJsonRpcResult() {
        String sseBody = """
            event: message\r
            data: {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"SSE result"}]}}\r
            \r
            """;
        HttpResponse<Buffer> response = mockJsonResponse(200, sseBody);
        when(response.getHeader("Content-Type")).thenReturn("text/event-stream");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w", CAP_TAG);
        Capability cap = WorkerTestSupport.testCapability(CAP_TAG);

        manager.submit(1L, instance, worker, cap, Map.of("channel", "#general"))
            .await().indefinitely();

        ArgumentCaptor<Map<String, Object>> outputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completionPublisher).complete(any(WorkerCorrelationContext.class), outputCaptor.capture());
        assertThat(outputCaptor.getValue()).containsKey("content");
        verify(faultPublisher, never()).fault(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    void schedulePersistedEvent_returnsVoid() {
        assertThat(manager.schedulePersistedEvent(new EventLog()).await().indefinitely()).isNull();
    }

    @Test
    void getActiveWorkCount_returnsZero() {
        assertThat(manager.getActiveWorkCount("any")).isEqualTo(0);
    }

    // --- test helpers ---

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
