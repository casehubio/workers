package io.casehub.workers.common;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerCorrelationContextTest {
    @Test
    void recordComponents() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "tenant-1";
        Worker worker = Worker.builder().name("w1").capabilityNames("cap").function(new WorkerFunction.Sync<>(Map.class,ctx -> WorkerResult.of(Map.of()))).build();
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(instance, worker, "hash-123", "tenant-1", null);
        assertThat(ctx.caseInstance()).isSameAs(instance);
        assertThat(ctx.worker()).isSameAs(worker);
        assertThat(ctx.idempotency()).isEqualTo("hash-123");
        assertThat(ctx.tenancyId()).isEqualTo("tenant-1");
    }

    @Test
    void bindingName_carriedThrough() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "tenant-1";
        Worker worker = Worker.builder().name("w1").capabilityNames("cap").function(new WorkerFunction.Sync<>(Map.class,ctx -> WorkerResult.of(Map.of()))).build();
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(
            instance, worker, "hash-123", "tenant-1", "my-binding");
        assertThat(ctx.bindingName()).isEqualTo("my-binding");
    }

    @Test
    void bindingName_nullable() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "tenant-1";
        Worker worker = Worker.builder().name("w1").capabilityNames("cap").function(new WorkerFunction.Sync<>(Map.class,ctx -> WorkerResult.of(Map.of()))).build();
        WorkerCorrelationContext ctx = new WorkerCorrelationContext(
            instance, worker, "hash-123", "tenant-1", null);
        assertThat(ctx.bindingName()).isNull();
    }
}
