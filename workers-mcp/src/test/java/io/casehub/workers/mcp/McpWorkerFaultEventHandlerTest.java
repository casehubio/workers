package io.casehub.workers.mcp;

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

class McpWorkerFaultEventHandlerTest {

    @Test
    void onFault_delegatesToWorkerFaultHandler() {
        WorkerFaultHandler faultHandler = mock(WorkerFaultHandler.class);
        McpWorkerFaultEventHandler handler = new McpWorkerFaultEventHandler();
        handler.workerFaultHandler = faultHandler;

        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "t1";
        Worker worker = Worker.builder().name("w1").capabilities(List.of()).function(ctx -> null).build();
        Capability capability = new Capability("mcp:slack:send-message", "", "");

        WorkflowExecutionFailed event = new WorkflowExecutionFailed(
            instance, worker, capability, "hash-1", "1",
            new RuntimeException("test"));

        when(faultHandler.handleFault(event)).thenReturn(Uni.createFrom().voidItem());

        handler.onFault(event).await().indefinitely();

        verify(faultHandler).handleFault(event);
    }
}
