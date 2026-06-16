package io.casehub.workers.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.BackoffStrategy;
import io.casehub.api.model.Capability;
import io.casehub.api.model.ExecutionPolicy;
import io.casehub.api.model.RetryPolicy;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class WorkerFaultHandlerTest {

    private WorkerFaultHandler handler;
    private WorkerRetrySupport retrySupport;
    private WorkerExecutionManager workerExecutionManager;
    private Vertx vertx;
    private EventLogRepository eventLogRepository;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        retrySupport = mock(WorkerRetrySupport.class);
        workerExecutionManager = mock(WorkerExecutionManager.class);
        vertx = Vertx.vertx();
        eventLogRepository = mock(EventLogRepository.class);

        handler = new WorkerFaultHandler();
        handler.retrySupport = retrySupport;
        handler.workerExecutionManager = workerExecutionManager;
        handler.vertx = vertx;
        handler.eventLogRepository = eventLogRepository;

        // Default: persistFailureLog succeeds
        when(retrySupport.persistFailureLog(any(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void permanentFault_skipsRetry() {
        CaseInstance instance = testCaseInstance();
        Worker worker = testWorker("w1");
        Capability cap = testCapability("cap");
        PermanentFaultException cause = new PermanentFaultException(400, "Bad Request");

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.handleFault(event).await().indefinitely();

        verify(retrySupport).persistFailureLog(instance, worker, "hash-1", "Bad Request", instance.tenancyId);
        verify(retrySupport).publishRetriesExhausted(instance.getUuid(), "w1", "hash-1");
        verify(retrySupport, never()).countFailedAttempts(any(), any(), any(), any());
        verify(workerExecutionManager, never()).submit(anyLong(), any(), any(), any(), any());
    }

    @Test
    void retryableFault_underMaxRetries_callsSubmit() {
        CaseInstance instance = testCaseInstance();
        RetryPolicy retryPolicy = new RetryPolicy(3, 100, BackoffStrategy.FIXED);
        ExecutionPolicy ep = new ExecutionPolicy(5000, retryPolicy);
        Worker worker = testWorker("w1");
        worker.setExecutionPolicy(ep);
        Capability cap = testCapability("cap");
        RuntimeException cause = new RuntimeException("transient error");

        // First failure — count=1, maxAttempts=3, so 1 < 3 → retry
        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(1L));

        EventLog eventLog = new EventLog();
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("key", "value");
        eventLog.setPayload(payload);
        when(eventLogRepository.findById(42L, instance.tenancyId))
            .thenReturn(Uni.createFrom().item(eventLog));

        when(workerExecutionManager.submit(anyLong(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.handleFault(event).await().indefinitely();

        // Verify submit was called with the reloaded input data
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workerExecutionManager).submit(
            eq(42L), eq(instance), eq(worker), eq(cap), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("key", "value");

        // Verify publishRetriesExhausted was NOT called
        verify(retrySupport, never()).publishRetriesExhausted(any(), any(), any());
    }

    @Test
    void retriesExhausted_neverCallsSubmit() {
        CaseInstance instance = testCaseInstance();
        RetryPolicy retryPolicy = new RetryPolicy(3, 10000, BackoffStrategy.FIXED);
        ExecutionPolicy ep = new ExecutionPolicy(5000, retryPolicy);
        Worker worker = testWorker("w1");
        worker.setExecutionPolicy(ep);
        Capability cap = testCapability("cap");
        RuntimeException cause = new RuntimeException("500 error");

        // failureCount == maxAttempts → strict < means exhausted
        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(3L));

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.handleFault(event).await().indefinitely();

        verify(retrySupport).publishRetriesExhausted(instance.getUuid(), "w1", "hash-1");
        verify(workerExecutionManager, never()).submit(anyLong(), any(), any(), any(), any());
    }

    @Test
    void retryAfterException_usesRetryAfterDelay() {
        CaseInstance instance = testCaseInstance();
        RetryPolicy retryPolicy = new RetryPolicy(3, 10000, BackoffStrategy.FIXED);
        ExecutionPolicy ep = new ExecutionPolicy(5000, retryPolicy);
        Worker worker = testWorker("w1");
        worker.setExecutionPolicy(ep);
        Capability cap = testCapability("cap");
        RetryAfterException cause = new RetryAfterException(200, "429 Too Many Requests");

        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(1L));

        EventLog eventLog = new EventLog();
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("key", "value");
        eventLog.setPayload(payload);
        when(eventLogRepository.findById(42L, instance.tenancyId))
            .thenReturn(Uni.createFrom().item(eventLog));

        when(workerExecutionManager.submit(anyLong(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        // With a real Vertx timer, the delay of 200ms should complete
        handler.handleFault(event).await().indefinitely();

        // Should have called submit (RetryAfter overrides configured backoff of 10000ms)
        verify(workerExecutionManager).submit(eq(42L), eq(instance), eq(worker), eq(cap), any());
        verify(retrySupport, never()).publishRetriesExhausted(any(), any(), any());
    }

    // ── Helpers ──

    private static CaseInstance testCaseInstance() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "test-tenant";
        return instance;
    }

    private static Worker testWorker(String name) {
        return Worker.builder().name(name).capabilities(List.of()).function(ctx -> null).build();
    }

    private static Capability testCapability(String tag) {
        return new Capability(tag, "", "");
    }
}
