package io.casehub.workers.common;

import static org.mockito.Mockito.*;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerFaultCallbackObserverTest {

    @Test
    void onFaultCallback_publishesFaultToAddressFromPendingCompletion() {
        WorkerFaultPublisher faultPublisher = mock(WorkerFaultPublisher.class);
        WorkerFaultCallbackObserver observer = new WorkerFaultCallbackObserver();
        observer.faultPublisher = faultPublisher;

        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        Worker worker = Worker.builder().name("w1").capabilities(List.of(new Capability("cap", "", ""))).function(ctx -> null).build();
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(instance, worker, "hash-1", "t1");
        PendingCompletion pending = new PendingCompletion(
            "dispatch-1", "camel", "casehub.workers.camel.fault",
            ctx, "token", new Capability("cap", "", ""), 42L,
            Instant.now(), Instant.now().plusSeconds(3600), Map.of());
        Throwable cause = new RuntimeException("callback fault");

        observer.onFaultCallback(new FaultCallbackEvent(pending, cause));

        verify(faultPublisher).fault(pending, cause);
    }
}
