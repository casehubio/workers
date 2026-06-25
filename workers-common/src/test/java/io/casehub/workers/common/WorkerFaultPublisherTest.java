package io.casehub.workers.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.workers.common.WorkerFaultEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerFaultPublisherTest {

    @Test
    void fault_fromContext_publishesToGivenAddress() {
        EventBus eventBus = mock(EventBus.class);
        WorkerFaultPublisher publisher = new WorkerFaultPublisher();
        publisher.eventBus = eventBus;

        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        Worker worker = Worker.builder().name("w1").capabilities(List.of(Capability.of("cap", "", ""))).function(new WorkerFunction.Sync(ctx -> WorkerResult.of(Map.of()))).build();
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(instance, worker, "hash-1", "t1");
        Capability capability = Capability.of("run-script", "", "");

        publisher.fault("casehub.workers.test.fault", ctx, capability, 99L,
            new RuntimeException("boom"));

        ArgumentCaptor<WorkerFaultEvent> captor = ArgumentCaptor.forClass(WorkerFaultEvent.class);
        verify(eventBus).publish(eq("casehub.workers.test.fault"), captor.capture());

        WorkerFaultEvent event = captor.getValue();
        assertThat(event.caseInstance()).isSameAs(instance);
        assertThat(event.worker()).isSameAs(worker);
        assertThat(event.capability()).isSameAs(capability);
        assertThat(event.inputDataHash()).isEqualTo("hash-1");
        assertThat(event.eventLogId()).isEqualTo("99");
    }

    @Test
    void fault_fromPendingCompletion_publishesToFaultAddress() {
        EventBus eventBus = mock(EventBus.class);
        WorkerFaultPublisher publisher = new WorkerFaultPublisher();
        publisher.eventBus = eventBus;

        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        Worker worker = Worker.builder().name("w1").capabilities(List.of(Capability.of("cap", "", ""))).function(new WorkerFunction.Sync(ctx -> WorkerResult.of(Map.of()))).build();
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(instance, worker, "hash-1", "t1");
        Capability capability = Capability.of("send-webhook", "", "");
        PendingCompletion pending = new PendingCompletion(
            "dispatch-1", "http", "casehub.workers.http.fault",
            ctx, "token", capability, 42L,
            Instant.now(), Instant.now().plusSeconds(3600), Map.of());
        Throwable cause = new RuntimeException("timeout");

        publisher.fault(pending, cause);

        ArgumentCaptor<WorkerFaultEvent> captor = ArgumentCaptor.forClass(WorkerFaultEvent.class);
        verify(eventBus).publish(eq("casehub.workers.http.fault"), captor.capture());

        WorkerFaultEvent event = captor.getValue();
        assertThat(event.caseInstance()).isSameAs(instance);
        assertThat(event.worker()).isSameAs(worker);
        assertThat(event.capability()).isSameAs(capability);
        assertThat(event.inputDataHash()).isEqualTo("hash-1");
        assertThat(event.eventLogId()).isEqualTo("42");
        assertThat(event.cause()).isSameAs(cause);
    }
}
