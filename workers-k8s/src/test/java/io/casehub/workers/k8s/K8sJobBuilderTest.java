package io.casehub.workers.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class K8sJobBuilderTest {

    private static JobDefinition imageDef(String name) {
        return new JobDefinition(name, "batch", "acme/" + name + ":latest",
            List.of("python"), List.of("run.py"), null,
            "500m", "2", "512Mi", "4Gi",
            3600, 600, 0, 1_048_576, "sa-batch",
            Map.of("team", "data"), Map.of("APP_MODE", "prod"), CleanupPolicy.DELETE);
    }

    @Test
    void build_imageBased_setsMetadata() {
        Job job = K8sJobBuilder.build(imageDef("report-gen"), "dispatch-123",
            "case-456", "tenant-1", "k8s:report-gen", "idem-789", "{}", "worker-1", 1L);

        assertThat(job.getMetadata().getNamespace()).isEqualTo("batch");
        assertThat(job.getMetadata().getName()).startsWith("casehub-report-gen-");
        assertThat(job.getMetadata().getName().length()).isLessThanOrEqualTo(57);
    }

    @Test
    void build_imageBased_setsLabels() {
        Job job = K8sJobBuilder.build(imageDef("report-gen"), "dispatch-123",
            "case-456", "tenant-1", "k8s:report-gen", "idem-789", "{}", "worker-1", 1L);

        Map<String, String> labels = job.getMetadata().getLabels();
        assertThat(labels).containsEntry("app.kubernetes.io/managed-by", "casehub");
        assertThat(labels).containsEntry("casehub.io/dispatch-id", "dispatch-123");
        assertThat(labels).containsEntry("casehub.io/capability", "k8s:report-gen");
        assertThat(labels).containsEntry("casehub.io/tenancy-id", "tenant-1");
        assertThat(labels).containsEntry("team", "data");
    }

    @Test
    void build_imageBased_setsJobSpec() {
        Job job = K8sJobBuilder.build(imageDef("report-gen"), "dispatch-123",
            "case-456", "tenant-1", "k8s:report-gen", "idem-789", "{}", "worker-1", 1L);

        assertThat(job.getSpec().getBackoffLimit()).isEqualTo(0);
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(3600L);
        assertThat(job.getSpec().getTtlSecondsAfterFinished()).isEqualTo(600);
    }

    @Test
    void build_imageBased_setsContainerSpec() {
        Job job = K8sJobBuilder.build(imageDef("report-gen"), "dispatch-123",
            "case-456", "tenant-1", "k8s:report-gen", "idem-789", "{}", "worker-1", 1L);

        var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(container.getImage()).isEqualTo("acme/report-gen:latest");
        assertThat(container.getCommand()).containsExactly("python");
        assertThat(container.getArgs()).containsExactly("run.py");
        assertThat(job.getSpec().getTemplate().getSpec().getRestartPolicy()).isEqualTo("Never");
        assertThat(job.getSpec().getTemplate().getSpec().getServiceAccountName()).isEqualTo("sa-batch");
    }

    @Test
    void build_imageBased_setsResourceLimits() {
        Job job = K8sJobBuilder.build(imageDef("report-gen"), "dispatch-123",
            "case-456", "tenant-1", "k8s:report-gen", "idem-789", "{}", "worker-1", 1L);

        var resources = job.getSpec().getTemplate().getSpec().getContainers().get(0).getResources();
        assertThat(resources.getRequests().get("cpu").toString()).isEqualTo("500m");
        assertThat(resources.getRequests().get("memory").toString()).isEqualTo("512Mi");
        assertThat(resources.getLimits().get("cpu").toString()).isEqualTo("2");
        assertThat(resources.getLimits().get("memory").toString()).isEqualTo("4Gi");
    }

    @Test
    void build_imageBased_setsCasehubEnvVars() {
        Job job = K8sJobBuilder.build(imageDef("report-gen"), "dispatch-123",
            "case-456", "tenant-1", "k8s:report-gen", "idem-789", "{\"key\":\"val\"}", "worker-1", 1L);

        var envVars = job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        assertThat(envVars).extracting(EnvVar::getName)
            .contains("CASEHUB_CASE_ID", "CASEHUB_TENANCY_ID", "CASEHUB_CAPABILITY",
                       "CASEHUB_IDEMPOTENCY", "CASEHUB_INPUT_DATA", "APP_MODE");
        assertThat(findEnv(envVars, "CASEHUB_CASE_ID")).isEqualTo("case-456");
        assertThat(findEnv(envVars, "CASEHUB_TENANCY_ID")).isEqualTo("tenant-1");
        assertThat(findEnv(envVars, "CASEHUB_INPUT_DATA")).isEqualTo("{\"key\":\"val\"}");
        assertThat(findEnv(envVars, "APP_MODE")).isEqualTo("prod");
    }

    @Test
    void build_slugDerivation_sanitizesSpecialChars() {
        JobDefinition def = new JobDefinition("My Report Gen!", "ns", "img:v1",
            List.of(), List.of(), null, null, null, null, null,
            3600, 600, 0, 1_048_576, null, Map.of(), Map.of(), CleanupPolicy.DELETE);

        Job job = K8sJobBuilder.build(def, "d1", "c1", "t1", "k8s:My Report Gen!", "i1", "{}", "worker-1", 1L);

        String name = job.getMetadata().getName();
        assertThat(name).startsWith("casehub-my-report-gen-");
        assertThat(name).matches("[a-z0-9-]+");
    }

    @Test
    void build_slugDerivation_truncatesLongNames() {
        String longName = "a".repeat(60);
        JobDefinition def = new JobDefinition(longName, "ns", "img:v1",
            List.of(), List.of(), null, null, null, null, null,
            3600, 600, 0, 1_048_576, null, Map.of(), Map.of(), CleanupPolicy.DELETE);

        Job job = K8sJobBuilder.build(def, "d1", "c1", "t1", "k8s:" + longName, "i1", "{}", "worker-1", 1L);

        assertThat(job.getMetadata().getName().length()).isLessThanOrEqualTo(57);
    }

    @Test
    void build_nullResourceLimits_omitsResources() {
        JobDefinition def = new JobDefinition("simple", "ns", "img:v1",
            List.of(), List.of(), null, null, null, null, null,
            3600, 600, 0, 1_048_576, null, Map.of(), Map.of(), CleanupPolicy.DELETE);

        Job job = K8sJobBuilder.build(def, "d1", "c1", "t1", "k8s:simple", "i1", "{}", "worker-1", 1L);

        var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(container.getResources().getRequests()).isNullOrEmpty();
        assertThat(container.getResources().getLimits()).isNullOrEmpty();
    }

    @Test
    void build_includesRecoveryLabels() {
        JobDefinition def = imageDef("report-gen");
        Job job = K8sJobBuilder.build(def, "dispatch-1", "case-uuid-1", "t1",
            "k8s:report-gen", "hash-abc", "{}", "w1", 42L);

        Map<String, String> labels = job.getMetadata().getLabels();
        assertThat(labels).containsEntry(K8sWorkerConstants.CASE_ID_LABEL, "case-uuid-1");
        assertThat(labels).containsEntry(K8sWorkerConstants.WORKER_NAME_LABEL, "w1");
        assertThat(labels).containsEntry(K8sWorkerConstants.EVENT_LOG_ID_LABEL, "42");
        assertThat(labels).containsEntry(K8sWorkerConstants.IDEMPOTENCY_LABEL, "hash-abc");
    }

    private static JobDefinition templateDef(String name) {
        return new JobDefinition(name, "batch", null, List.of(), List.of(),
            "templates/" + name + ".yaml",
            null, null, null, null, 3600, 600, 0, 1_048_576,
            null, Map.of(), Map.of(), CleanupPolicy.DELETE);
    }

    @Test
    void build_templatePath_includesRecoveryLabels() {
        JobDefinition def = templateDef("templated");
        Job job = K8sJobBuilder.build(def, "dispatch-2", "case-uuid-2", "t1",
            "k8s:templated", "hash-def", "{}", "w2", 99L);

        Map<String, String> labels = job.getMetadata().getLabels();
        assertThat(labels).containsEntry(K8sWorkerConstants.CASE_ID_LABEL, "case-uuid-2");
        assertThat(labels).containsEntry(K8sWorkerConstants.WORKER_NAME_LABEL, "w2");
        assertThat(labels).containsEntry(K8sWorkerConstants.EVENT_LOG_ID_LABEL, "99");
        assertThat(labels).containsEntry(K8sWorkerConstants.IDEMPOTENCY_LABEL, "hash-def");
    }

    private static String findEnv(List<EnvVar> envVars, String name) {
        return envVars.stream()
            .filter(e -> name.equals(e.getName()))
            .map(EnvVar::getValue)
            .findFirst().orElse(null);
    }
}
