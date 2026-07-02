package io.casehub.workers.k8s;

import static org.mockito.Mockito.*;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.workers.common.WorkerFaultEvent;
import io.casehub.workers.common.WorkerFaultHandler;
import io.casehub.workers.testing.WorkerTestSupport;
import io.smallrye.mutiny.Uni;
import java.util.Map;
import org.junit.jupiter.api.Test;

class K8sWorkerFaultEventHandlerTest {

    @Test
    void onFault_delegatesToWorkerFaultHandler() {
        WorkerFaultHandler faultHandler = mock(WorkerFaultHandler.class);
        K8sWorkerFaultEventHandler handler = new K8sWorkerFaultEventHandler();
        handler.workerFaultHandler = faultHandler;

        var instance = WorkerTestSupport.testCaseInstance("t1");
        Worker worker = Worker.builder()
            .name("w1")
            .capabilityName("k8s:test")
            .function(new WorkerFunction.Sync(ctx -> WorkerResult.of(Map.of())))
            .build();
        Capability capability = WorkerTestSupport.testCapability("k8s:test");

        WorkerFaultEvent event = new WorkerFaultEvent(
            instance, worker, capability, "hash-1", "1",
            new RuntimeException("job failed"));

        when(faultHandler.handleFault(event)).thenReturn(Uni.createFrom().voidItem());

        handler.onFault(event).await().indefinitely();

        verify(faultHandler).handleFault(event);
    }
}
