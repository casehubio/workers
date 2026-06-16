package io.casehub.workers.githubactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.RetryAfterException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.common.WorkflowCompletionPublisher;
import io.casehub.workers.testing.WorkerTestSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class GitHubActionsWorkerExecutionManagerTest {

    private GitHubActionsWorkerExecutionManager manager;
    private GitHubActionsTokenResolver tokenResolver;
    private WorkerFaultPublisher faultPublisher;
    private WorkflowCompletionPublisher completionPublisher;
    private WebClient webClient;
    private HttpRequest<Buffer> request;

    @BeforeEach
    void setUp() {
        tokenResolver = mock(GitHubActionsTokenResolver.class);
        faultPublisher = mock(WorkerFaultPublisher.class);
        completionPublisher = mock(WorkflowCompletionPublisher.class);
        webClient = mock(WebClient.class);
        request = mock(HttpRequest.class);

        manager = new GitHubActionsWorkerExecutionManager();
        manager.tokenResolver = tokenResolver;
        manager.faultPublisher = faultPublisher;
        manager.completionPublisher = completionPublisher;
        manager.webClient = webClient;

        when(tokenResolver.resolve(anyString())).thenReturn("ghp_test_token");
        when(tokenResolver.apiBaseUrl()).thenReturn("https://api.github.com");
        when(webClient.requestAbs(any(HttpMethod.class), anyString())).thenReturn(request);
        when(request.putHeader(anyString(), anyString())).thenReturn(request);
    }

    @Test
    void workflowDispatch_204_completesWithOutput() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        Map<String, Object> inputData = Map.of(
            "owner", "casehubio", "repo", "devtown",
            "workflow_id", "ci.yml", "ref", "main");

        manager.submit(1L, instance, worker, cap, inputData).await().indefinitely();

        ArgumentCaptor<Map<String, Object>> outputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(completionPublisher).complete(any(WorkerCorrelationContext.class), outputCaptor.capture());
        assertThat(outputCaptor.getValue())
            .containsEntry("dispatched", true)
            .containsEntry("owner", "casehubio")
            .containsEntry("repo", "devtown");
        verify(faultPublisher, never()).fault(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    void workflowDispatch_correctUrl() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        verify(webClient).requestAbs(HttpMethod.POST,
            "https://api.github.com/repos/casehubio/devtown/actions/workflows/ci.yml/dispatches");
    }

    @Test
    void workflowDispatch_requestBody_withInputs() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        Map<String, Object> inputData = new HashMap<>();
        inputData.put("owner", "casehubio");
        inputData.put("repo", "devtown");
        inputData.put("workflow_id", "ci.yml");
        inputData.put("ref", "main");
        inputData.put("inputs", Map.of("environment", "staging"));

        manager.submit(1L, instance, worker, cap, inputData).await().indefinitely();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(request).sendJson(bodyCaptor.capture());
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(body).containsEntry("ref", "main");
        assertThat(body).containsKey("inputs");
        assertThat((Map<String, Object>) body.get("inputs")).containsEntry("environment", "staging");
    }

    @Test
    void workflowDispatch_requestBody_withoutInputs() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(request).sendJson(bodyCaptor.capture());
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(body).containsEntry("ref", "main");
        assertThat(body).doesNotContainKey("inputs");
    }

    @Test
    void repositoryDispatch_204_completesWithOutput() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "event_type", "upstream-published"))
            .await().indefinitely();

        verify(completionPublisher).complete(any(WorkerCorrelationContext.class), any());
    }

    @Test
    void repositoryDispatch_correctUrl() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "event_type", "upstream-published"))
            .await().indefinitely();

        verify(webClient).requestAbs(HttpMethod.POST,
            "https://api.github.com/repos/casehubio/devtown/dispatches");
    }

    @Test
    void repositoryDispatch_requestBody_withClientPayload() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

        Map<String, Object> inputData = new HashMap<>();
        inputData.put("owner", "casehubio");
        inputData.put("repo", "devtown");
        inputData.put("event_type", "upstream-published");
        inputData.put("client_payload", Map.of("source", "casehub-engine"));

        manager.submit(1L, instance, worker, cap, inputData).await().indefinitely();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(request).sendJson(bodyCaptor.capture());
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(body).containsEntry("event_type", "upstream-published");
        assertThat(body).containsKey("client_payload");
        assertThat((Map<String, Object>) body.get("client_payload")).containsEntry("source", "casehub-engine");
    }

    @Test
    void repositoryDispatch_requestBody_withoutClientPayload() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "event_type", "upstream-published"))
            .await().indefinitely();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(request).sendJson(bodyCaptor.capture());
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertThat(body).containsEntry("event_type", "upstream-published");
        assertThat(body).doesNotContainKey("client_payload");
    }

    @Test
    void workflowDispatch_missingOwner_permanentFault() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("repo", "devtown", "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void workflowDispatch_missingRef_permanentFault() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown", "workflow_id", "ci.yml"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void repositoryDispatch_missingEventType_permanentFault() {
        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void workflowDispatch_422_retryAfter60s() {
        HttpResponse<Buffer> response = mockResponse(422);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(RetryAfterException.class);
        assertThat(((RetryAfterException) causeCaptor.getValue()).retryAfterMs()).isEqualTo(60000L);
    }

    @Test
    void repositoryDispatch_422_permanentFault() {
        HttpResponse<Buffer> response = mockResponse(422);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_REPOSITORY_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "event_type", "upstream-published"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void response_429_withRetryAfter() {
        HttpResponse<Buffer> response = mockResponse(429);
        when(response.getHeader("Retry-After")).thenReturn("30");
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(RetryAfterException.class);
        assertThat(((RetryAfterException) causeCaptor.getValue()).retryAfterMs()).isEqualTo(30000L);
    }

    @Test
    void response_403_permanentFault() {
        HttpResponse<Buffer> response = mockResponse(403);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
        assertThat(((PermanentFaultException) causeCaptor.getValue()).statusCode()).isEqualTo(403);
    }

    @Test
    void response_401_permanentFault() {
        HttpResponse<Buffer> response = mockResponse(401);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void response_404_permanentFault() {
        HttpResponse<Buffer> response = mockResponse(404);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void response_500_retryableFault() {
        HttpResponse<Buffer> response = mockResponse(500);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        ArgumentCaptor<Throwable> causeCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT),
            any(), eq(cap), eq(1L), causeCaptor.capture());
        assertThat(causeCaptor.getValue())
            .isNotInstanceOf(PermanentFaultException.class)
            .isNotInstanceOf(RetryAfterException.class);
    }

    @Test
    void customApiBaseUrl_usedInUrl() {
        when(tokenResolver.apiBaseUrl()).thenReturn("https://github.example.com/api/v3");
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        verify(webClient).requestAbs(HttpMethod.POST,
            "https://github.example.com/api/v3/repos/casehubio/devtown/actions/workflows/ci.yml/dispatches");
    }

    @Test
    void noCasehubHeaders_onOutboundRequest() {
        HttpResponse<Buffer> response = mockResponse(204);
        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(response));

        CaseInstance instance = WorkerTestSupport.testCaseInstance();
        Worker worker = WorkerTestSupport.testWorker("w",
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);
        Capability cap = WorkerTestSupport.testCapability(
            GitHubActionsWorkerConstants.CAPABILITY_WORKFLOW_DISPATCH);

        manager.submit(1L, instance, worker, cap,
            Map.of("owner", "casehubio", "repo", "devtown",
                   "workflow_id", "ci.yml", "ref", "main"))
            .await().indefinitely();

        verify(request).putHeader("Authorization", "Bearer ghp_test_token");
        verify(request).putHeader("Accept", "application/vnd.github+json");
        verify(request).putHeader("X-GitHub-Api-Version", "2022-11-28");
        verify(request, never()).putHeader(eq("X-CaseHub-Idempotency"), anyString());
        verify(request, never()).putHeader(eq("X-CaseHub-Case-Id"), anyString());
    }

    @Test
    void schedulePersistedEvent_returnsVoid() {
        assertThat(manager.schedulePersistedEvent(new EventLog()).await().indefinitely()).isNull();
    }

    @Test
    void getActiveWorkCount_returnsZero() {
        assertThat(manager.getActiveWorkCount("any")).isEqualTo(0);
    }

    private HttpResponse<Buffer> mockResponse(int status) {
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.statusMessage()).thenReturn("Status " + status);
        when(response.getHeader(anyString())).thenReturn(null);
        return response;
    }
}
