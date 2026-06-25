package io.casehub.workers.testing;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkerTestSupport {

    private WorkerTestSupport() {}

    public static CaseInstance testCaseInstance() {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = "test-tenant";
        return instance;
    }

    public static CaseInstance testCaseInstance(String tenancyId) {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());
        instance.tenancyId = tenancyId;
        return instance;
    }

    public static Worker testWorker(String name, String... capabilityTags) {
        List<Capability> caps = java.util.Arrays.stream(capabilityTags)
            .map(tag -> Capability.of(tag, "", ""))
            .toList();
        return Worker.builder().name(name).capabilities(caps)
            .function(new WorkerFunction.Sync(ctx -> WorkerResult.of(Map.of())))
            .build();
    }

    public static Capability testCapability(String tag) {
        return Capability.of(tag, "", "");
    }
}
