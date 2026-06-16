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

class WorkerCompletionExpiryObserverTest {

    @Test
    void onExpiry_publishesFaultToAddressFromPendingCompletion() {
        WorkerFaultPublisher faultPublisher = mock(WorkerFaultPublisher.class);
        WorkerCompletionExpiryObserver observer = new WorkerCompletionExpiryObserver();
        observer.faultPublisher = faultPublisher;

        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        Worker worker = new Worker("w1", List.of(new Capability("cap", "", "")), (ctx) -> null);
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(instance, worker, "hash-1", "t1");
        PendingCompletion pending = new PendingCompletion(
            "dispatch-1", "http", "casehub.workers.http.fault",
            ctx, "token", new Capability("cap", "", ""), 42L,
            Instant.now(), Instant.now().plusSeconds(3600), Map.of());

        observer.onExpiry(new CompletionExpiredEvent(pending));

        verify(faultPublisher).fault(eq(pending), any(RuntimeException.class));
    }
}
