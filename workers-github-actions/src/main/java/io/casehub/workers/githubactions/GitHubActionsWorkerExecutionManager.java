package io.casehub.workers.githubactions;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.RetryAfterException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkerRetrySupport;
import io.casehub.workers.common.WorkflowCompletionPublisher;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitHubActionsWorkerExecutionManager implements WorkerExecutionManager {

    private static final Logger LOG = Logger.getLogger(GitHubActionsWorkerExecutionManager.class);

    @Inject GitHubActionsTokenResolver tokenResolver;
    @Inject WorkerFaultPublisher faultPublisher;
    @Inject WorkflowCompletionPublisher completionPublisher;
    @Inject io.vertx.mutiny.core.Vertx vertx;

    WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                            Capability capability, Map<String, Object> inputData) {
        String capTag = capability.getName();
        boolean isWorkflowDispatch = GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH.equals(capTag);

        String owner = stringField(inputData, "owner");
        String repo = stringField(inputData, "repo");
        if (owner == null || repo == null) {
            faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT,
                buildCtx(instance, worker, capability, inputData),
                capability, eventLogId,
                new PermanentFaultException(0, "Missing required inputData: owner, repo"));
            return Uni.createFrom().voidItem();
        }

        String url;
        Map<String, Object> body;

        if (isWorkflowDispatch) {
            String workflowId = stringField(inputData, "workflow_id");
            String ref = stringField(inputData, "ref");
            if (workflowId == null || ref == null) {
                faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT,
                    buildCtx(instance, worker, capability, inputData),
                    capability, eventLogId,
                    new PermanentFaultException(0,
                        "Missing required inputData for workflow-dispatch: workflow_id, ref"));
                return Uni.createFrom().voidItem();
            }
            url = tokenResolver.apiBaseUrl() + "/repos/" + owner + "/" + repo
                + "/actions/workflows/" + workflowId + "/dispatches";
            body = new LinkedHashMap<>();
            body.put("ref", ref);
            Object inputs = inputData.get("inputs");
            if (inputs != null) {
                body.put("inputs", inputs);
            }
        } else {
            String eventType = stringField(inputData, "event_type");
            if (eventType == null) {
                faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT,
                    buildCtx(instance, worker, capability, inputData),
                    capability, eventLogId,
                    new PermanentFaultException(0,
                        "Missing required inputData for repository-dispatch: event_type"));
                return Uni.createFrom().voidItem();
            }
            url = tokenResolver.apiBaseUrl() + "/repos/" + owner + "/" + repo + "/dispatches";
            body = new LinkedHashMap<>();
            body.put("event_type", eventType);
            Object clientPayload = inputData.get("client_payload");
            if (clientPayload != null) {
                body.put("client_payload", clientPayload);
            }
        }

        String token;
        try {
            token = tokenResolver.resolve(owner);
        } catch (PermanentFaultException e) {
            faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT,
                buildCtx(instance, worker, capability, inputData),
                capability, eventLogId, e);
            return Uni.createFrom().voidItem();
        }

        WorkerCorrelationContext ctx = buildCtx(instance, worker, capability, inputData);

        HttpRequest<Buffer> request = webClient.requestAbs(HttpMethod.POST, url);
        request.putHeader("Authorization", "Bearer " + token);
        request.putHeader("Accept", "application/vnd.github+json");
        request.putHeader("X-GitHub-Api-Version", "2022-11-28");

        return request.sendJson(body)
            .flatMap(response -> {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    completionPublisher.complete(ctx, Map.of(
                        "dispatched", true, "owner", owner, "repo", repo));
                    return Uni.createFrom().<Void>voidItem();
                }
                if (status == 422) {
                    if (isWorkflowDispatch) {
                        throw new RetryAfterException(60_000,
                            "422 — workflow_dispatch trigger may be cached (GE-20260426-805acb)");
                    } else {
                        throw new PermanentFaultException(status,
                            status + " " + response.statusMessage());
                    }
                }
                if (status == 429) {
                    throw WorkerRetrySupport.parseRetryAfter(
                        response.getHeader("Retry-After"), status, response.statusMessage());
                }
                if (status >= 400 && status < 500) {
                    throw new PermanentFaultException(status,
                        status + " " + response.statusMessage());
                }
                throw new RuntimeException(status + " " + response.statusMessage());
            })
            .onFailure().recoverWithUni(t -> {
                faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT, ctx, capability, eventLogId, t);
                return Uni.createFrom().voidItem();
            });
    }

    @Override
    public Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return 0;
    }

    private WorkerCorrelationContext buildCtx(CaseInstance instance, Worker worker,
                                              Capability capability,
                                              Map<String, Object> inputData) {
        String idempotency = WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.getName(), capability.getName(), inputData);
        return new WorkerCorrelationContext(instance, worker, idempotency, instance.tenancyId);
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }
}
