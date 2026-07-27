package io.casehub.workers.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerFaultEventTest {

    @Test
    void bindingName_carriedThrough() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        Worker worker = Worker.builder().name("w1").capabilityNames("cap").function(new WorkerFunction.Sync<>(Map.class, Map.class, (ctx, scope) -> WorkerResult.of(Map.of()))).build();
        Capability capability = Capability.of("test-cap", "", "");
        Throwable cause = new RuntimeException("test");

        WorkerFaultEvent event = new WorkerFaultEvent(
            instance, worker, capability, "hash", "42", cause, "my-binding");

        assertThat(event.bindingName()).isEqualTo("my-binding");
    }

    @Test
    void bindingName_nullable() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        Worker worker = Worker.builder().name("w1").capabilityNames("cap").function(new WorkerFunction.Sync<>(Map.class, Map.class, (ctx, scope) -> WorkerResult.of(Map.of()))).build();
        Capability capability = Capability.of("test-cap", "", "");
        Throwable cause = new RuntimeException("test");

        WorkerFaultEvent event = new WorkerFaultEvent(
            instance, worker, capability, "hash", "42", cause, null);

        assertThat(event.bindingName()).isNull();
    }
}
