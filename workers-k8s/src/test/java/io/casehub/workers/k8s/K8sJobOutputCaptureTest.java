package io.casehub.workers.k8s;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class K8sJobOutputCaptureTest {

    KubernetesClient client;
    MixedOperation<Pod, PodList, PodResource> podOp;
    NonNamespaceOperation<Pod, PodList, PodResource> nsOp;
    FilterWatchListDeletable<Pod, PodList, PodResource> labelOp;
    PodResource podResource;

    @BeforeEach
    void setUp() {
        client = mock(KubernetesClient.class);
        podOp = mock(MixedOperation.class);
        nsOp = mock(NonNamespaceOperation.class);
        labelOp = mock(FilterWatchListDeletable.class);
        podResource = mock(PodResource.class);

        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace(anyString())).thenReturn(nsOp);
        when(nsOp.withLabel(anyString(), anyString())).thenReturn(labelOp);
        when(nsOp.withName(anyString())).thenReturn(podResource);
    }

    @Test
    void capture_jsonObjectStdout_parsedAsStructuredOutput() {
        setupPod("pod-1", 0, "{\"result\":\"ok\"}");

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).containsEntry("result", "ok");
    }

    @Test
    void capture_nonJsonStdout_wrappedAsRaw() {
        setupPod("pod-1", 0, "hello world");

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).containsEntry("stdout", "hello world");
        assertThat(output).containsEntry("exitCode", 0);
    }

    @Test
    void capture_emptyStdout_returnsEmptyMap() {
        setupPod("pod-1", 0, "");

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).containsEntry("stdout", "");
        assertThat(output).containsEntry("exitCode", 0);
    }

    @Test
    void capture_noPods_returnsEmptyMap() {
        PodList emptyList = new PodList();
        emptyList.setItems(List.of());
        when(labelOp.list()).thenReturn(emptyList);

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).isEmpty();
    }

    @Test
    void capture_exceptionReadingLogs_returnsEmptyMap() {
        PodList podList = new PodList();
        Pod pod = buildPod("pod-1", 0);
        podList.setItems(List.of(pod));
        when(labelOp.list()).thenReturn(podList);
        when(podResource.getLog()).thenThrow(new RuntimeException("logs unavailable"));

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).isEmpty();
    }

    @Test
    void capture_jsonArrayStdout_wrappedAsRaw() {
        setupPod("pod-1", 0, "[1,2,3]");

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).containsEntry("stdout", "[1,2,3]");
        assertThat(output).containsEntry("exitCode", 0);
    }

    @Test
    void capture_multiplePods_selectsLastByCreationTime() {
        PodList podList = new PodList();
        Pod pod1 = buildPod("pod-1", 1);
        pod1.getMetadata().setCreationTimestamp("2026-07-01T10:00:00Z");
        Pod pod2 = buildPod("pod-2", 0);
        pod2.getMetadata().setCreationTimestamp("2026-07-01T10:05:00Z");
        podList.setItems(List.of(pod1, pod2));
        when(labelOp.list()).thenReturn(podList);
        when(podResource.getLog()).thenReturn("{\"retry\":\"success\"}");

        Map<String, Object> output = K8sJobOutputCapture.capture(client, "batch", "my-job", 1_048_576);

        assertThat(output).containsEntry("retry", "success");
    }

    private void setupPod(String podName, int exitCode, String logs) {
        PodList podList = new PodList();
        Pod pod = buildPod(podName, exitCode);
        podList.setItems(List.of(pod));
        when(labelOp.list()).thenReturn(podList);
        when(podResource.getLog()).thenReturn(logs);
    }

    private Pod buildPod(String name, int exitCode) {
        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setCreationTimestamp("2026-07-01T10:00:00Z");
        pod.setMetadata(meta);
        PodStatus status = new PodStatus();
        ContainerStatus cs = new ContainerStatus();
        ContainerState state = new ContainerState();
        ContainerStateTerminated terminated = new ContainerStateTerminated();
        terminated.setExitCode(exitCode);
        state.setTerminated(terminated);
        cs.setState(state);
        status.setContainerStatuses(List.of(cs));
        pod.setStatus(status);
        return pod;
    }
}
