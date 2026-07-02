package io.casehub.workers.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.workers.common.AsyncWorkerCompletionRegistry;
import io.casehub.workers.common.PendingCompletion;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkerProvisioningException;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;

@WorkerBackend
@Priority(10)
@ApplicationScoped
public class K8sWorkerExecutionManager implements WorkerExecutionManager {

    private static final Logger LOG = Logger.getLogger(K8sWorkerExecutionManager.class);

    @Inject JobDefinitionResolver resolver;
    @Inject AsyncWorkerCompletionRegistry registry;
    @Inject WorkerFaultPublisher faultPublisher;
    @Inject KubernetesClient kubernetesClient;
    @Inject K8sJobInformerManager informerManager;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "casehub.workers.k8s.max-input-bytes", defaultValue = "262144")
    long maxInputBytes;

    @Override
    public boolean supports(String capabilityName, String tenancyId) {
        return resolver.canResolve(capabilityName, tenancyId);
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return registry.countByWorkerName(workerId);
    }

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                            Capability capability, Map<String, Object> inputData) {
        JobDefinition definition;
        try {
            definition = resolver.resolve(capability.name(), instance.tenancyId);
        } catch (WorkerProvisioningException e) {
            WorkerCorrelationContext ctx = buildCtx(instance, worker, capability, inputData);
            faultPublisher.fault(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT,
                ctx, capability, eventLogId, new PermanentFaultException(0, e.getMessage()));
            return Uni.createFrom().voidItem();
        }

        WorkerCorrelationContext ctx = buildCtx(instance, worker, capability, inputData);

        String inputDataJson;
        try {
            inputDataJson = objectMapper.writeValueAsString(inputData);
        } catch (Exception e) {
            faultPublisher.fault(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT,
                ctx, capability, eventLogId, new PermanentFaultException(0,
                    "Failed to serialize inputData: " + e.getMessage()));
            return Uni.createFrom().voidItem();
        }

        if (inputDataJson.getBytes().length > maxInputBytes) {
            faultPublisher.fault(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT,
                ctx, capability, eventLogId, new PermanentFaultException(0,
                    "Input data (" + inputDataJson.getBytes().length
                        + " bytes) exceeds maxInputBytes limit (" + maxInputBytes + ")"));
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().item(() -> {
            PendingCompletion pending = registry.register(
                K8sWorkerConstants.WORKER_TYPE,
                K8sWorkerEventBusAddresses.K8S_WORKER_FAULT,
                ctx, capability, eventLogId,
                Duration.ofSeconds(definition.timeoutSeconds() + 300),
                Map.of(
                    "cleanup", definition.cleanup().name(),
                    "maxOutputBytes", String.valueOf(definition.maxOutputBytes())
                ));

            try {
                Job job = K8sJobBuilder.build(definition, pending.dispatchId(),
                    instance.getUuid().toString(), instance.tenancyId,
                    capability.name(), ctx.idempotency(), inputDataJson);
                kubernetesClient.resource(job).create();
            } catch (KubernetesClientException e) {
                registry.complete(pending.dispatchId());
                throw classifyApiServerError(e);
            } catch (Exception e) {
                registry.complete(pending.dispatchId());
                throw e;
            }
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
          .replaceWithVoid()
          .onFailure().recoverWithUni(t -> {
              faultPublisher.fault(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT,
                  ctx, capability, eventLogId, t);
              return Uni.createFrom().voidItem();
          });
    }

    private RuntimeException classifyApiServerError(KubernetesClientException e) {
        int code = e.getCode();
        if (code == 403) return new PermanentFaultException(403, "Forbidden: " + e.getMessage());
        if (code == 404) return new PermanentFaultException(404, "Namespace not found: " + e.getMessage());
        if (code == 422) return new PermanentFaultException(422, "Invalid Job spec: " + e.getMessage());
        return new RuntimeException(e.getMessage(), e);
    }

    private WorkerCorrelationContext buildCtx(CaseInstance instance, Worker worker,
                                              Capability capability,
                                              Map<String, Object> inputData) {
        String idempotency = WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.name(), capability.name(), inputData);
        return new WorkerCorrelationContext(instance, worker, idempotency, instance.tenancyId);
    }
}
