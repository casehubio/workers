package io.casehub.workers.http;

import static org.mockito.Mockito.*;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.workers.common.WorkerFaultHandler;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpWorkerFaultEventHandlerTest {

    @Test
    void onFault_delegatesToWorkerFaultHandler() {
        WorkerFaultHandler faultHandler = mock(WorkerFaultHandler.class);
        HttpWorkerFaultEventHandler handler = new HttpWorkerFaultEventHandler();
        handler.workerFaultHandler = faultHandler;

        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "t1";
        Worker worker = new Worker("w1", List.of(), (ctx) -> null);
        Capability capability = new Capability("cap", "", "");

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, capability, "hash-1", "1",
            new RuntimeException("test"));

        when(faultHandler.handleFault(event)).thenReturn(Uni.createFrom().voidItem());

        handler.onFault(event).await().indefinitely();

        verify(faultHandler).handleFault(event);
    }
}
