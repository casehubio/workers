package io.casehub.workers.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.RetryPolicy;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.RetryAfterException;
import io.casehub.workers.common.WorkerRetrySupport;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpWorkerFaultEventHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Logger LOG = Logger.getLogger(McpWorkerFaultEventHandler.class);

    @Inject WorkerRetrySupport retrySupport;
    @Inject WorkerExecutionManager workerExecutionManager;
    @Inject Vertx vertx;
    @Inject EventLogRepository eventLogRepository;

    @ConsumeEvent(value = McpWorkerEventBusAddresses.MCP_WORKER_FAULT, blocking = true)
    public Uni<Void> onFault(WorkflowExecutionFailed event) {
        CaseInstance instance = event.caseInstance();
        Worker worker = event.worker();
        String inputDataHash = event.inputDataHash();
        String tenancyId = instance.tenancyId;
        String errorMsg = (event.cause() != null && event.cause().getMessage() != null)
            ? event.cause().getMessage() : "unknown";

        return retrySupport.persistFailureLog(instance, worker, inputDataHash, errorMsg, tenancyId)
            .flatMap(ignored -> {
                if (event.cause() instanceof PermanentFaultException) {
                    retrySupport.publishRetriesExhausted(
                        instance.getUuid(), worker.getName(), inputDataHash);
                    return Uni.createFrom().voidItem();
                }

                return retrySupport.countFailedAttempts(
                        instance.getUuid(), worker.getName(), inputDataHash, tenancyId)
                    .flatMap(failureCount -> {
                        RetryPolicy retryPolicy = WorkerRetrySupport.resolveRetryPolicy(worker);
                        if (failureCount < retryPolicy.maxAttempts()) {
                            long delayMs;
                            if (event.cause() instanceof RetryAfterException ra) {
                                delayMs = ra.retryAfterMs();
                            } else {
                                delayMs = WorkerRetrySupport.computeBackoffDelayMs(
                                    retryPolicy, failureCount + 1);
                            }
                            return reloadAndResubmit(event, delayMs);
                        } else {
                            retrySupport.publishRetriesExhausted(
                                instance.getUuid(), worker.getName(), inputDataHash);
                            return Uni.createFrom().voidItem();
                        }
                    });
            })
            .onFailure().recoverWithUni(ex -> {
                LOG.errorf(ex, "Fault handling failed for worker %s case %s — case may stall",
                           worker.getName(), instance.getUuid());
                return Uni.createFrom().voidItem();
            });
    }

    private Uni<Void> reloadAndResubmit(WorkflowExecutionFailed event, long delayMs) {
        return eventLogRepository
            .findById(Long.parseLong(event.eventLogId()), event.caseInstance().tenancyId)
            .flatMap(eventLog -> {
                Map<String, Object> inputData =
                    OBJECT_MAPPER.convertValue(eventLog.getPayload(), MAP_TYPE);
                return Uni.createFrom().<Void>emitter(em -> {
                        long timerId = vertx.setTimer(delayMs, id -> em.complete(null));
                        em.onTermination(() -> vertx.cancelTimer(timerId));
                    })
                    .flatMap(ignored -> workerExecutionManager.submit(
                        Long.parseLong(event.eventLogId()),
                        event.caseInstance(), event.worker(), event.capability(), inputData));
            });
    }
}
