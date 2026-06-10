package io.casehub.workers.githubactions;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.RetryAfterException;
import io.casehub.workers.common.WorkerRetrySupport;
import io.casehub.workers.testing.WorkerTestSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class GitHubActionsWorkerFaultEventHandlerTest {

    private GitHubActionsWorkerFaultEventHandler handler;
    private WorkerRetrySupport retrySupport;
    private WorkerExecutionManager workerExecutionManager;
    private Vertx vertx;
    private EventLogRepository eventLogRepository;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        retrySupport = mock(WorkerRetrySupport.class);
        workerExecutionManager = mock(WorkerExecutionManager.class);
        vertx = mock(Vertx.class);
        eventLogRepository = mock(EventLogRepository.class);

        handler = new GitHubActionsWorkerFaultEventHandler();
        handler.retrySupport = retrySupport;
        handler.workerExecutionManager = workerExecutionManager;
        handler.vertx = vertx;
        handler.eventLogRepository = eventLogRepository;

        when(retrySupport.persistFailureLog(any(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void persistFailureLog_calledForEveryFault() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w1", "cap");
        Capability cap = WorkerTestSupport.testCapability("cap");
        RuntimeException cause = new RuntimeException("boom");

        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(3L));

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.onFault(event).await().indefinitely();

        verify(retrySupport).persistFailureLog(instance, worker, "hash-1", "boom", instance.tenancyId);
    }

    @Test
    void permanentFault_publishesExhaustedImmediately() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w1", "cap");
        Capability cap = WorkerTestSupport.testCapability("cap");
        PermanentFaultException cause = new PermanentFaultException(400, "Bad Request");

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.onFault(event).await().indefinitely();

        verify(retrySupport).publishRetriesExhausted(instance.getUuid(), "w1", "hash-1");
        verify(retrySupport, never()).countFailedAttempts(any(), any(), any(), any());
    }

    @Test
    void retryAfterException_usesRetryAfterDelay() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w1", "cap");
        Capability cap = WorkerTestSupport.testCapability("cap");
        RetryAfterException cause = new RetryAfterException(60000, "422 workflow_dispatch cache");

        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(1L));

        EventLog eventLog = new EventLog();
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("owner", "casehubio");
        eventLog.setPayload(payload);
        when(eventLogRepository.findById(42L, instance.tenancyId))
            .thenReturn(Uni.createFrom().item(eventLog));

        when(vertx.setTimer(anyLong(), any(Handler.class))).thenAnswer(invocation -> {
            Handler<Long> timerHandler = invocation.getArgument(1);
            timerHandler.handle(1L);
            return 1L;
        });

        when(workerExecutionManager.submit(anyLong(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.onFault(event).await().indefinitely();

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(vertx).setTimer(delayCaptor.capture(), any(Handler.class));
        assertThat(delayCaptor.getValue()).isEqualTo(60000L);
    }

    @Test
    void normalFault_usesConfiguredBackoff() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        RetryPolicy retryPolicy = new RetryPolicy(3, 10000, BackoffStrategy.FIXED);
        ExecutionPolicy ep = new ExecutionPolicy(5000, retryPolicy);
        Worker worker = WorkerTestSupport.testWorker("w1", "cap");
        worker.setExecutionPolicy(ep);
        Capability cap = WorkerTestSupport.testCapability("cap");
        RuntimeException cause = new RuntimeException("500 Internal Server Error");

        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(1L));

        EventLog eventLog = new EventLog();
        ObjectNode payload = OBJECT_MAPPER.createObjectNode().put("owner", "casehubio");
        eventLog.setPayload(payload);
        when(eventLogRepository.findById(42L, instance.tenancyId))
            .thenReturn(Uni.createFrom().item(eventLog));

        when(vertx.setTimer(anyLong(), any(Handler.class))).thenAnswer(invocation -> {
            Handler<Long> timerHandler = invocation.getArgument(1);
            timerHandler.handle(1L);
            return 1L;
        });

        when(workerExecutionManager.submit(anyLong(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.onFault(event).await().indefinitely();

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(vertx).setTimer(delayCaptor.capture(), any(Handler.class));
        assertThat(delayCaptor.getValue()).isEqualTo(10000L);
    }

    @Test
    void exhausted_afterMaxAttempts() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        RetryPolicy retryPolicy = new RetryPolicy(3, 10000, BackoffStrategy.FIXED);
        ExecutionPolicy ep = new ExecutionPolicy(5000, retryPolicy);
        Worker worker = WorkerTestSupport.testWorker("w1", "cap");
        worker.setExecutionPolicy(ep);
        Capability cap = WorkerTestSupport.testCapability("cap");
        RuntimeException cause = new RuntimeException("500 error");

        when(retrySupport.countFailedAttempts(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().item(3L));

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.onFault(event).await().indefinitely();

        verify(retrySupport).publishRetriesExhausted(instance.getUuid(), "w1", "hash-1");
        verify(vertx, never()).setTimer(anyLong(), any(Handler.class));
    }

    @Test
    void faultHandlingFailure_logsAndRecovers() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w1", "cap");
        Capability cap = WorkerTestSupport.testCapability("cap");
        RuntimeException cause = new RuntimeException("error");

        when(retrySupport.persistFailureLog(any(), any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("DB down")));

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, cap, "hash-1", "42", cause);

        handler.onFault(event).await().indefinitely();

        verify(retrySupport, never()).publishRetriesExhausted(any(), any(), any());
        verify(workerExecutionManager, never()).submit(anyLong(), any(), any(), any(), any());
    }
}
