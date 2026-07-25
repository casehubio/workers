package io.casehub.workers.githubactions;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.RetryAfterException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkerRetrySupport;
import io.casehub.workers.common.WorkflowCompletionPublisher;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

@WorkerBackend
@Priority(10)
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
    public void submit(Long eventLogId, CaseInstance instance, Worker worker,
                       Capability capability, Map<String, Object> inputData) {
        submit(eventLogId, instance, worker, capability, inputData, null);
    }

    @Override
    public void submit(Long eventLogId, CaseInstance instance, Worker worker,
                       Capability capability, Map<String, Object> inputData,
                       String bindingName) {
        String  capTag             = capability.name();
        boolean isWorkflowDispatch = GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH.equals(capTag);

        String owner = stringField(inputData, "owner");
        String repo  = stringField(inputData, "repo");
        if (owner == null || repo == null) {
            faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT,
                                 buildCtx(instance, worker, capability, inputData, bindingName),
                                 capability, eventLogId,
                                 new PermanentFaultException(0, "Missing required inputData: owner, repo"));
            return;
        }

        String              url;
        Map<String, Object> body;

        if (isWorkflowDispatch) {
            String workflowId = stringField(inputData, "workflow_id");
            String ref        = stringField(inputData, "ref");
            if (workflowId == null || ref == null) {
                faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT,
                                     buildCtx(instance, worker, capability, inputData, bindingName),
                                     capability, eventLogId,
                                     new PermanentFaultException(0,
                                                                 "Missing required inputData for workflow-dispatch: workflow_id, ref"));
                return;
            }
            url  = tokenResolver.apiBaseUrl() + "/repos/" + owner + "/" + repo
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
                                     buildCtx(instance, worker, capability, inputData, bindingName),
                                     capability, eventLogId,
                                     new PermanentFaultException(0,
                                                                 "Missing required inputData for repository-dispatch: event_type"));
                return;
            }
            url  = tokenResolver.apiBaseUrl() + "/repos/" + owner + "/" + repo + "/dispatches";
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
                                 buildCtx(instance, worker, capability, inputData, bindingName),
                                 capability, eventLogId, e);
            return;
        }

        WorkerCorrelationContext ctx = buildCtx(instance, worker, capability, inputData, bindingName);

        HttpRequest<Buffer> request = webClient.requestAbs(HttpMethod.POST, url);
        request.putHeader("Authorization", "Bearer " + token);
        request.putHeader("Accept", "application/vnd.github+json");
        request.putHeader("X-GitHub-Api-Version", "2022-11-28");

        try {
            var response = request.sendJson(body).await().indefinitely();
            int status   = response.statusCode();
            if (status >= 200 && status < 300) {
                completionPublisher.complete(ctx, Map.of(
                        "dispatched", true, "owner", owner, "repo", repo));
                return;
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
        } catch (Exception t) {
            faultPublisher.fault(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT, ctx, capability, eventLogId, t);
        }
    }

    @Override
    public boolean supports(String capabilityName, String tenancyId) {
        return GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH.equals(capabilityName)
            || GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH.equals(capabilityName);
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return 0;
    }

    private WorkerCorrelationContext buildCtx(CaseInstance instance, Worker worker,
                                              Capability capability,
                                              Map<String, Object> inputData,
                                              String bindingName) {
        String idempotency = WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.name(), capability.name(), inputData);
        return new WorkerCorrelationContext(instance, worker, idempotency, instance.tenancyId, bindingName);
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }
}
