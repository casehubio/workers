package io.casehub.workers.common;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.model.CaseInstance;

public record WorkerFaultEvent(
    CaseInstance caseInstance,
    Worker worker,
    Capability capability,
    String inputDataHash,
    String eventLogId,
    Throwable cause) {}
