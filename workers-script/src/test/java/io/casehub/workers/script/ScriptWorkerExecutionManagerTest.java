package io.casehub.workers.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkflowCompletionPublisher;
import io.casehub.workers.testing.WorkerTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptWorkerExecutionManagerTest {

    ScriptWorkerExecutionManager manager;
    ScriptDefinitionResolver resolver;
    WorkerFaultPublisher faultPublisher;
    WorkflowCompletionPublisher completionPublisher;

    @BeforeEach
    void setUp() {
        manager = new ScriptWorkerExecutionManager();
        resolver = new ScriptDefinitionResolver();
        faultPublisher = mock(WorkerFaultPublisher.class);
        completionPublisher = mock(WorkflowCompletionPublisher.class);

        manager.scriptDefinitionResolver = resolver;
        manager.faultPublisher = faultPublisher;
        manager.completionPublisher = completionPublisher;
        manager.init();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private WorkerCorrelationContext testCtx(CaseInstance instance, Worker worker) {
        return new WorkerCorrelationContext(instance, worker, "test-idempotency", instance.tenancyId, null);
    }

    // --- executeProcess tests: call directly so exceptions surface in surefire ---

    @Test
    void executeProcess_jsonObjectStdout_parsedAsStructuredOutput() {
        ScriptDefinition def = new ScriptDefinition("json-script", "/bin/sh",
            List.of("-c", "echo '{\"result\":\"ok\"}'"),
            null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:json-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:json-script"));

        Map<String, Object> output = manager.executeProcess(def, Map.of(), ctx, capability);

        assertThat(output).containsEntry("result", "ok");
    }

    @Test
    void executeProcess_nonJsonStdout_wrappedAsRaw() {
        ScriptDefinition def = new ScriptDefinition("text-script", "/bin/sh",
            List.of("-c", "echo hello world"),
            null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:text-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:text-script"));

        Map<String, Object> output = manager.executeProcess(def, Map.of(), ctx, capability);

        assertThat(output.get("stdout").toString()).contains("hello world");
        assertThat(output).containsKey("stderr");
        assertThat(output).containsEntry("exitCode", 0);
    }

    @Test
    void executeProcess_jsonArrayStdout_fallsThroughToRawWrapper() {
        ScriptDefinition def = new ScriptDefinition("array-script", "/bin/sh",
            List.of("-c", "echo '[1,2,3]'"),
            null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:array-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:array-script"));

        Map<String, Object> output = manager.executeProcess(def, Map.of(), ctx, capability);

        assertThat(output.get("stdout").toString()).contains("[1,2,3]");
        assertThat(output).containsEntry("exitCode", 0);
    }

    @Test
    void executeProcess_nonZeroExit_throwsRuntimeException() {
        ScriptDefinition def = new ScriptDefinition("fail-script", "/bin/sh",
            List.of("-c", "exit 1"),
            null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:fail-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:fail-script"));

        assertThatThrownBy(() -> manager.executeProcess(def, Map.of(), ctx, capability))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("exited with code 1");
    }

    @Test
    void executeProcess_timeout_throwsPermanentFault() {
        ScriptDefinition def = new ScriptDefinition("slow-script", "/bin/sh",
            List.of("-c", "sleep 60"),
            null, Map.of(), 1, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:slow-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:slow-script"));

        assertThatThrownBy(() -> manager.executeProcess(def, Map.of(), ctx, capability))
            .isInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("timed out");
    }

    @Test
    void executeProcess_stdinDelivery_inputDataReachesProcess() {
        ScriptDefinition def = new ScriptDefinition("stdin-script", "/bin/sh",
            List.of("-c", "cat"),
            null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:stdin-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:stdin-script"));

        Map<String, Object> output = manager.executeProcess(def, Map.of("key", "value"), ctx, capability);

        assertThat(output).containsEntry("key", "value");
    }

    @Test
    void executeProcess_environmentVariablesSet() {
        ScriptDefinition def = new ScriptDefinition("env-script", "/bin/sh",
            List.of("-c", "echo $CASEHUB_CAPABILITY"),
            null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:env-script");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:env-script"));

        Map<String, Object> output = manager.executeProcess(def, Map.of(), ctx, capability);

        assertThat(output.get("stdout").toString()).contains("script:env-script");
    }

    @Test
    void executeProcess_commandNotFound_throwsPermanentFault() {
        ScriptDefinition def = new ScriptDefinition("missing-cmd",
            "/nonexistent/command/xyz123",
            List.of(), null, Map.of(), 30, 1_048_576);

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Capability capability = WorkerTestSupport.testCapability("script:missing-cmd");
        WorkerCorrelationContext ctx = testCtx(instance, WorkerTestSupport.testWorker("w1", "script:missing-cmd"));

        assertThatThrownBy(() -> manager.executeProcess(def, Map.of(), ctx, capability))
            .isInstanceOf(PermanentFaultException.class)
            .hasMessageContaining("Command not found");
    }

    // --- submit integration tests: verify fault routing and binding propagation ---

    @Test
    void submit_unknownCapability_permanentFault() {
        resolver.initialize(Map.of());

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "script:missing"),
            WorkerTestSupport.testCapability("script:missing"),
            Map.of());

        verify(faultPublisher).fault(
            eq(ScriptWorkerEventBusAddresses.SCRIPT_WORKER_FAULT),
            any(WorkerCorrelationContext.class),
            any(Capability.class), eq(1L), any(PermanentFaultException.class));
    }

    @Test
    void submit_6arg_passesBindingNameThroughCompletion() {
        resolver.initialize(Map.of(
            "echo-script", new ScriptDefinition("echo-script", "/bin/sh",
                List.of("-c", "echo '{\"result\":\"ok\"}'"),
                null, Map.of(), 30, 1_048_576)
        ));

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "script:echo-script"),
            WorkerTestSupport.testCapability("script:echo-script"),
            Map.of(), "binding-x");

        verifyNoFaults();
        ArgumentCaptor<WorkerCorrelationContext> ctxCaptor =
            ArgumentCaptor.forClass(WorkerCorrelationContext.class);
        verify(completionPublisher).complete(ctxCaptor.capture(), any());
        assertThat(ctxCaptor.getValue().bindingName()).isEqualTo("binding-x");
    }

    @Test
    void submit_5arg_passesNullBindingName() {
        resolver.initialize(Map.of(
            "echo-script2", new ScriptDefinition("echo-script2", "/bin/sh",
                List.of("-c", "echo '{\"result\":\"ok\"}'"),
                null, Map.of(), 30, 1_048_576)
        ));

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "script:echo-script2"),
            WorkerTestSupport.testCapability("script:echo-script2"),
            Map.of());

        verifyNoFaults();
        ArgumentCaptor<WorkerCorrelationContext> ctxCaptor =
            ArgumentCaptor.forClass(WorkerCorrelationContext.class);
        verify(completionPublisher).complete(ctxCaptor.capture(), any());
        assertThat(ctxCaptor.getValue().bindingName()).isNull();
    }

    @Test
    void supports_delegatesToResolver() {
        resolver.initialize(Map.of(
            "script-1", new ScriptDefinition("script-1", "/bin/sh", List.of("-c", "echo ok"), null, Map.of(), 30, 1_048_576)
        ));

        assertThat(manager.supports("script:script-1", "t1")).isTrue();
        assertThat(manager.supports("script:script-2", "t1")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void verifyNoFaults() {
        var invocations = mockingDetails(faultPublisher).getInvocations();
        if (!invocations.isEmpty()) {
            ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(faultPublisher).fault(anyString(), any(), any(), anyLong(), errorCaptor.capture());
            throw new AssertionError("Expected completion but process faulted", errorCaptor.getValue());
        }
    }
}
