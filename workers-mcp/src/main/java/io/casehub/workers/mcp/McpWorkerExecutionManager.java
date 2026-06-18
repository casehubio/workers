package io.casehub.workers.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkerRetrySupport;
import io.casehub.workers.common.WorkflowCompletionPublisher;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpWorkerExecutionManager implements WorkerExecutionManager {

    private static final Logger LOG = Logger.getLogger(McpWorkerExecutionManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    /** JSON-RPC error codes that indicate permanent (non-retryable) faults. */
    private static final Set<Integer> PERMANENT_ERROR_CODES = Set.of(
        -32600,  // Invalid Request
        -32601,  // Method not found
        -32602,  // Invalid params
        -32700   // Parse error
    );

    @Inject McpServerResolver serverResolver;
    @Inject McpSessionManager sessionManager;
    @Inject WorkerFaultPublisher faultPublisher;
    @Inject WorkflowCompletionPublisher completionPublisher;
    @Inject io.vertx.mutiny.core.Vertx vertx;

    WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                            Capability capability, Map<String, Object> inputData) {
        String capTag = capability.getName();
        String serverName = McpServerResolver.parseServerName(capTag);
        String toolName = McpServerResolver.parseToolName(capTag);

        // Resolve server — failure is permanent
        ResolvedMcpServer server;
        try {
            server = serverResolver.resolve(capTag, instance.tenancyId);
        } catch (Exception e) {
            faultPublisher.fault(McpWorkerEventBusAddresses.MCP_WORKER_FAULT,
                buildCtx(instance, worker, capability, inputData),
                capability, eventLogId,
                new PermanentFaultException(0, e.getMessage()));
            return Uni.createFrom().voidItem();
        }

        WorkerCorrelationContext ctx = buildCtx(instance, worker, capability, inputData);

        return sessionManager.getOrInitialize(serverName)
            .flatMap(session -> {
                long requestId = session.nextRequestId();

                ObjectNode jsonRpcRequest = buildJsonRpcRequest(toolName, inputData, requestId);

                HttpRequest<Buffer> request = webClient.requestAbs(HttpMethod.POST, server.url());
                request.putHeader("Content-Type", "application/json");
                request.putHeader("Accept", "application/json, text/event-stream");
                request.putHeader("MCP-Protocol-Version", session.protocolVersion());
                if (session.hasSessionId()) {
                    request.putHeader("Mcp-Session-Id", session.sessionId());
                }
                server.headers().forEach(request::putHeader);
                request.timeout(server.timeoutSeconds() * 1000L);

                return request.sendJson(jsonRpcRequest)
                    .flatMap(response -> {
                        int status = response.statusCode();

                        if (status >= 200 && status < 300) {
                            return handleSuccessResponse(response, requestId, ctx);
                        }
                        if (status == 404) {
                            if (session.hasSessionId()) {
                                sessionManager.invalidate(serverName);
                                throw new RuntimeException("404 — MCP session expired, invalidated");
                            } else {
                                throw new PermanentFaultException(404, "MCP endpoint not found");
                            }
                        }
                        if (status == 429) {
                            throw WorkerRetrySupport.parseRetryAfter(
                                response.getHeader("Retry-After"), status, response.statusMessage());
                        }
                        if (status >= 400 && status < 500) {
                            throw new PermanentFaultException(status,
                                status + " " + response.statusMessage());
                        }
                        // 5xx
                        throw new RuntimeException(status + " " + response.statusMessage());
                    });
            })
            .onFailure().recoverWithUni(t -> {
                faultPublisher.fault(McpWorkerEventBusAddresses.MCP_WORKER_FAULT, ctx, capability, eventLogId, t);
                return Uni.createFrom().voidItem();
            });
    }

    @Override
    public Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return 0;
    }

    private ObjectNode buildJsonRpcRequest(String toolName, Map<String, Object> inputData, long requestId) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("method", "tools/call");
        root.put("id", requestId);
        ObjectNode params = root.putObject("params");
        params.put("name", toolName);
        params.set("arguments", OBJECT_MAPPER.valueToTree(inputData));
        return root;
    }

    private Uni<Void> handleSuccessResponse(io.vertx.mutiny.ext.web.client.HttpResponse<Buffer> response,
                                             long requestId,
                                             WorkerCorrelationContext ctx) {
        String contentType = response.getHeader("Content-Type");
        String body = response.bodyAsString();

        JsonNode jsonRpc;
        try {
            if (contentType != null && contentType.startsWith("text/event-stream")) {
                jsonRpc = parseSSE(body, requestId);
                if (jsonRpc == null) {
                    throw new RuntimeException("No matching JSON-RPC response found in SSE stream");
                }
            } else {
                jsonRpc = OBJECT_MAPPER.readTree(body);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Malformed MCP response: " + e.getMessage());
        }

        return handleJsonRpcResponse(jsonRpc, ctx);
    }

    private Uni<Void> handleJsonRpcResponse(JsonNode jsonRpc, WorkerCorrelationContext ctx) {
        // Check for JSON-RPC error
        if (jsonRpc.has("error")) {
            JsonNode error = jsonRpc.get("error");
            int code = error.has("code") ? error.get("code").asInt() : 0;
            String message = error.has("message") ? error.get("message").asText() : "Unknown error";

            if (PERMANENT_ERROR_CODES.contains(code)) {
                throw new PermanentFaultException(0, "JSON-RPC error " + code + ": " + message);
            }
            // -32603 (Internal error) and all others are retryable
            throw new RuntimeException("JSON-RPC error " + code + ": " + message);
        }

        // Must have result
        JsonNode result = jsonRpc.get("result");
        if (result == null) {
            throw new RuntimeException("Malformed MCP response: missing 'result'");
        }

        // Check isError flag — retryable
        if (result.has("isError") && result.get("isError").asBoolean()) {
            String text = extractContentText(result);
            throw new RuntimeException("MCP tool returned isError: " + text);
        }

        // Build output
        Map<String, Object> output;
        if (result.has("structuredContent") && result.get("structuredContent").isObject()) {
            output = OBJECT_MAPPER.convertValue(result.get("structuredContent"), MAP_TYPE);
        } else if (result.has("content")) {
            List<Map<String, Object>> contentList = OBJECT_MAPPER.convertValue(result.get("content"), LIST_MAP_TYPE);
            output = Map.of("content", contentList);
        } else {
            output = Map.of();
        }

        completionPublisher.complete(ctx, output);
        return Uni.createFrom().voidItem();
    }

    private String extractContentText(JsonNode result) {
        if (!result.has("content") || !result.get("content").isArray()) {
            return "(no content)";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : result.get("content")) {
            if (item.has("text")) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(item.get("text").asText());
            }
        }
        return sb.isEmpty() ? "(no text)" : sb.toString();
    }

    /**
     * Parses an SSE body to extract the JSON-RPC response matching the expected request id.
     * Events are separated by double newlines. Within each event, lines starting with
     * "data:" carry the payload.
     */
    private JsonNode parseSSE(String body, long expectedId) {
        if (body == null || body.isBlank()) return null;
        String[] events = body.split("\n\n");
        for (String event : events) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\n")) {
                if (line.startsWith("data: ")) {
                    data.append(line.substring(6));
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (data.isEmpty()) continue;
            try {
                JsonNode json = OBJECT_MAPPER.readTree(data.toString());
                if ((json.has("result") || json.has("error"))
                        && json.has("id") && json.get("id").asLong() == expectedId) {
                    return json;
                }
            } catch (Exception e) {
                LOG.tracef("Skipping unparseable SSE event: %s", e.getMessage());
            }
        }
        return null;
    }

    private WorkerCorrelationContext buildCtx(CaseInstance instance, Worker worker,
                                              Capability capability,
                                              Map<String, Object> inputData) {
        String idempotency = WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.getName(), capability.getName(), inputData);
        return new WorkerCorrelationContext(instance, worker, idempotency, instance.tenancyId);
    }
}
