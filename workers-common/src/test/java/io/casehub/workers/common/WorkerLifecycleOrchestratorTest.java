package io.casehub.workers.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerLifecycleOrchestratorTest {

    @Test
    void initializeAll_callsInitializeOnEachRuntime() {
        WorkerRuntime runtime1 = mock(WorkerRuntime.class);
        when(runtime1.workerType()).thenReturn("http");
        when(runtime1.initialize()).thenReturn(Uni.createFrom().voidItem());
        when(runtime1.status()).thenReturn(WorkerRuntimeStatus.RUNNING);
        when(runtime1.capabilities()).thenReturn(Set.of("http:send-email"));

        WorkerRuntime runtime2 = mock(WorkerRuntime.class);
        when(runtime2.workerType()).thenReturn("camel");
        when(runtime2.initialize()).thenReturn(Uni.createFrom().voidItem());
        when(runtime2.status()).thenReturn(WorkerRuntimeStatus.RUNNING);
        when(runtime2.capabilities()).thenReturn(Set.of("camel:ftp"));

        WorkerLifecycleOrchestrator orchestrator = new WorkerLifecycleOrchestrator();
        orchestrator.initializeAll(List.of(runtime1, runtime2));

        verify(runtime1).initialize();
        verify(runtime2).initialize();
    }

    @Test
    void initializeAll_faultedRuntime_doesNotPreventOthers() {
        WorkerRuntime runtime1 = mock(WorkerRuntime.class);
        when(runtime1.workerType()).thenReturn("http");
        when(runtime1.initialize()).thenReturn(Uni.createFrom().failure(new RuntimeException("init failed")));

        WorkerRuntime runtime2 = mock(WorkerRuntime.class);
        when(runtime2.workerType()).thenReturn("camel");
        when(runtime2.initialize()).thenReturn(Uni.createFrom().voidItem());
        when(runtime2.status()).thenReturn(WorkerRuntimeStatus.RUNNING);
        when(runtime2.capabilities()).thenReturn(Set.of("camel:ftp"));

        WorkerLifecycleOrchestrator orchestrator = new WorkerLifecycleOrchestrator();
        orchestrator.initializeAll(List.of(runtime1, runtime2));

        verify(runtime1).initialize();
        verify(runtime2).initialize();
    }

    @Test
    void initializeAll_emptyList_noErrors() {
        WorkerLifecycleOrchestrator orchestrator = new WorkerLifecycleOrchestrator();

        assertThatCode(() -> orchestrator.initializeAll(Collections.emptyList()))
            .doesNotThrowAnyException();
    }

    @Test
    void shutdownAll_callsShutdownOnRunningAndFaulted() {
        WorkerRuntime running = mock(WorkerRuntime.class);
        when(running.workerType()).thenReturn("http");
        when(running.status()).thenReturn(WorkerRuntimeStatus.RUNNING);
        when(running.shutdown()).thenReturn(Uni.createFrom().voidItem());

        WorkerRuntime faulted = mock(WorkerRuntime.class);
        when(faulted.workerType()).thenReturn("camel");
        when(faulted.status()).thenReturn(WorkerRuntimeStatus.FAULTED);
        when(faulted.shutdown()).thenReturn(Uni.createFrom().voidItem());

        WorkerRuntime pending = mock(WorkerRuntime.class);
        when(pending.workerType()).thenReturn("mcp");
        when(pending.status()).thenReturn(WorkerRuntimeStatus.PENDING);

        WorkerLifecycleOrchestrator orchestrator = new WorkerLifecycleOrchestrator();
        orchestrator.shutdownAll(List.of(running, faulted, pending));

        verify(running).shutdown();
        verify(faulted).shutdown();
        verify(pending, never()).shutdown();
    }

    @Test
    void shutdownAll_failureDoesNotPreventOthers() {
        WorkerRuntime runtime1 = mock(WorkerRuntime.class);
        when(runtime1.workerType()).thenReturn("http");
        when(runtime1.status()).thenReturn(WorkerRuntimeStatus.RUNNING);
        when(runtime1.shutdown()).thenReturn(Uni.createFrom().failure(new RuntimeException("shutdown failed")));

        WorkerRuntime runtime2 = mock(WorkerRuntime.class);
        when(runtime2.workerType()).thenReturn("camel");
        when(runtime2.status()).thenReturn(WorkerRuntimeStatus.RUNNING);
        when(runtime2.shutdown()).thenReturn(Uni.createFrom().voidItem());

        WorkerLifecycleOrchestrator orchestrator = new WorkerLifecycleOrchestrator();
        orchestrator.shutdownAll(List.of(runtime1, runtime2));

        verify(runtime1).shutdown();
        verify(runtime2).shutdown();
    }
}
