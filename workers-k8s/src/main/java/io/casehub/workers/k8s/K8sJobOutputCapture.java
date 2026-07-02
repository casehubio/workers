package io.casehub.workers.k8s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class K8sJobOutputCapture {

    private static final Logger LOG = Logger.getLogger(K8sJobOutputCapture.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private K8sJobOutputCapture() {}

    public static Map<String, Object> capture(KubernetesClient client, String namespace,
                                               String jobName, long maxOutputBytes) {
        try {
            PodList pods = client.pods().inNamespace(namespace)
                .withLabel("job-name", jobName).list();

            if (pods.getItems().isEmpty()) {
                LOG.warnf("No Pods found for Job %s/%s — returning empty output", namespace, jobName);
                return Map.of();
            }

            Pod lastPod = pods.getItems().stream()
                .max(Comparator.comparing(p -> p.getMetadata().getCreationTimestamp()))
                .orElse(null);

            int exitCode = extractExitCode(lastPod);
            String logs = client.pods().inNamespace(namespace)
                .withName(lastPod.getMetadata().getName()).getLog();

            if (logs != null && logs.length() > maxOutputBytes) {
                logs = logs.substring(0, (int) maxOutputBytes);
            }

            return parseOutput(logs != null ? logs : "", exitCode);
        } catch (Exception e) {
            LOG.warnf("Failed to capture output for Job %s/%s: %s", namespace, jobName, e.getMessage());
            return Map.of();
        }
    }

    static int extractExitCode(Pod pod) {
        try {
            List<ContainerStatus> statuses = pod.getStatus().getContainerStatuses();
            if (statuses != null && !statuses.isEmpty()) {
                var terminated = statuses.get(0).getState().getTerminated();
                if (terminated != null) {
                    return terminated.getExitCode();
                }
            }
        } catch (NullPointerException ignored) {}
        return -1;
    }

    static Map<String, Object> parseOutput(String stdout, int exitCode) {
        String trimmed = stdout.trim();
        if (!trimmed.isEmpty() && trimmed.startsWith("{")) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                if (node.isObject()) {
                    return OBJECT_MAPPER.readValue(trimmed, MAP_TYPE);
                }
            } catch (JsonProcessingException ignored) {}
        }
        return Map.of("stdout", stdout, "exitCode", exitCode);
    }
}
