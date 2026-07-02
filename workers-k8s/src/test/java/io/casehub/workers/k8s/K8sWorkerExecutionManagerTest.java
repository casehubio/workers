package io.casehub.workers.k8s;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.worker.api.Capability;
import io.casehub.workers.common.AsyncWorkerCompletionRegistry;
import io.casehub.workers.common.PendingCompletion;
import io.casehub.workers.common.PermanentFaultException;
import io.casehub.workers.common.WorkerCorrelationContext;
import io.casehub.workers.common.WorkerFaultPublisher;
import io.casehub.workers.testing.WorkerTestSupport;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class K8sWorkerExecutionManagerTest {

    K8sWorkerExecutionManager manager;
    JobDefinitionResolver resolver;
    AsyncWorkerCompletionRegistry registry;
    WorkerFaultPublisher faultPublisher;
    KubernetesClient client;
    K8sJobInformerManager informerManager;

    @BeforeEach
    void setUp() {
        manager = new K8sWorkerExecutionManager();
        resolver = new JobDefinitionResolver();
        registry = mock(AsyncWorkerCompletionRegistry.class);
        faultPublisher = mock(WorkerFaultPublisher.class);
        client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        informerManager = mock(K8sJobInformerManager.class);

        manager.resolver = resolver;
        manager.registry = registry;
        manager.faultPublisher = faultPublisher;
        manager.kubernetesClient = client;
        manager.informerManager = informerManager;
        manager.objectMapper = new ObjectMapper();
        manager.maxInputBytes = 262144;

        when(informerManager.isNamespaceAvailable(anyString())).thenReturn(true);
    }

    private static JobDefinition imageDef(String name) {
        return new JobDefinition(name, "batch", "acme/" + name + ":latest",
            List.of(), List.of(), null, null, null, null, null,
            3600, 600, 0, 1_048_576, null, Map.of(), Map.of(), CleanupPolicy.DELETE);
    }

    @Test
    void submit_createsJobAndRegisters() {
        resolver.initialize(Map.of("report-gen", imageDef("report-gen")));
        PendingCompletion pending = mock(PendingCompletion.class);
        when(pending.dispatchId()).thenReturn("dispatch-1");
        when(registry.register(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenReturn(pending);
        NamespaceableResource<Job> resource = mock(NamespaceableResource.class);
        when(client.resource(any(Job.class))).thenReturn(resource);
        when(resource.create()).thenReturn(new Job());

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "k8s:report-gen"),
            WorkerTestSupport.testCapability("k8s:report-gen"),
            Map.of("key", "value")).await().indefinitely();

        verify(registry).register(eq(K8sWorkerConstants.WORKER_TYPE),
            eq(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT),
            any(), any(), eq(1L), any(Duration.class), any());
        verify(resource).create();
    }

    @Test
    void submit_unknownCapability_publishesPermanentFault() {
        resolver.initialize(Map.of());

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "k8s:missing"),
            WorkerTestSupport.testCapability("k8s:missing"),
            Map.of()).await().indefinitely();

        verify(faultPublisher).fault(eq(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT),
            any(WorkerCorrelationContext.class), any(Capability.class), eq(1L),
            any(PermanentFaultException.class));
    }

    @Test
    void submit_createFailure403_cleansUpRegistryAndFaultsPermanent() {
        resolver.initialize(Map.of("test", imageDef("test")));
        PendingCompletion pending = mock(PendingCompletion.class);
        when(pending.dispatchId()).thenReturn("dispatch-1");
        when(registry.register(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenReturn(pending);
        NamespaceableResource<Job> resource = mock(NamespaceableResource.class);
        when(client.resource(any(Job.class))).thenReturn(resource);
        when(resource.create()).thenThrow(new KubernetesClientException("Forbidden", 403, null));

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "k8s:test"),
            WorkerTestSupport.testCapability("k8s:test"),
            Map.of()).await().indefinitely();

        verify(registry).complete("dispatch-1");
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT),
            any(), any(), eq(1L), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PermanentFaultException.class);
    }

    @Test
    void submit_createFailure409_cleansUpRegistryAndFaultsRetryable() {
        resolver.initialize(Map.of("test", imageDef("test")));
        PendingCompletion pending = mock(PendingCompletion.class);
        when(pending.dispatchId()).thenReturn("dispatch-1");
        when(registry.register(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenReturn(pending);
        NamespaceableResource<Job> resource = mock(NamespaceableResource.class);
        when(client.resource(any(Job.class))).thenReturn(resource);
        when(resource.create()).thenThrow(new KubernetesClientException("Conflict", 409, null));

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "k8s:test"),
            WorkerTestSupport.testCapability("k8s:test"),
            Map.of()).await().indefinitely();

        verify(registry).complete("dispatch-1");
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT),
            any(), any(), eq(1L), captor.capture());
        assertThat(captor.getValue()).isNotInstanceOf(PermanentFaultException.class);
    }

    @Test
    void submit_inputDataExceedsMaxBytes_permanentFault() {
        resolver.initialize(Map.of("test", imageDef("test")));
        manager.maxInputBytes = 10;

        manager.submit(1L,
            WorkerTestSupport.testCaseInstance(),
            WorkerTestSupport.testWorker("w1", "k8s:test"),
            WorkerTestSupport.testCapability("k8s:test"),
            Map.of("large", "x".repeat(100))).await().indefinitely();

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(faultPublisher).fault(eq(K8sWorkerEventBusAddresses.K8S_WORKER_FAULT),
            any(), any(), eq(1L), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PermanentFaultException.class);
        assertThat(captor.getValue().getMessage()).contains("exceeds maxInputBytes");
    }

    @Test
    void supports_delegatesToResolver() {
        resolver.initialize(Map.of("x", imageDef("x")));

        assertThat(manager.supports("k8s:x", "t1")).isTrue();
        assertThat(manager.supports("k8s:y", "t1")).isFalse();
    }

    @Test
    void getActiveWorkCount_delegatesToRegistry() {
        when(registry.countByWorkerName("w1")).thenReturn(3);

        assertThat(manager.getActiveWorkCount("w1")).isEqualTo(3);
    }
}
