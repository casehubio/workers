package io.casehub.workers.k8s;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class K8sJobBuilder {

    private static final Logger LOG = Logger.getLogger(K8sJobBuilder.class);

    private K8sJobBuilder() {}

    public static Job build(JobDefinition definition, String dispatchId,
                            String caseId, String tenancyId, String capabilityTag,
                            String idempotency, String inputDataJson) {
        if (definition.template() != null && !definition.template().isBlank()) {
            return buildFromTemplate(definition, dispatchId, caseId, tenancyId,
                capabilityTag, idempotency, inputDataJson);
        }
        return buildFromImage(definition, dispatchId, caseId, tenancyId,
            capabilityTag, idempotency, inputDataJson);
    }

    private static Job buildFromImage(JobDefinition def, String dispatchId,
                                       String caseId, String tenancyId,
                                       String capabilityTag, String idempotency,
                                       String inputDataJson) {
        String jobName = generateJobName(def.name());
        Map<String, String> labels = buildLabels(def, dispatchId, capabilityTag, tenancyId);
        List<EnvVar> envVars = buildEnvVars(def, caseId, tenancyId, capabilityTag,
            idempotency, inputDataJson);

        ContainerBuilder containerBuilder = new ContainerBuilder()
            .withName("job")
            .withImage(def.image())
            .withEnv(envVars);

        if (!def.command().isEmpty()) containerBuilder.withCommand(def.command());
        if (!def.args().isEmpty()) containerBuilder.withArgs(def.args());

        Map<String, Quantity> requests = new LinkedHashMap<>();
        Map<String, Quantity> limits = new LinkedHashMap<>();
        if (def.cpuRequest() != null) requests.put("cpu", new Quantity(def.cpuRequest()));
        if (def.memoryRequest() != null) requests.put("memory", new Quantity(def.memoryRequest()));
        if (def.cpuLimit() != null) limits.put("cpu", new Quantity(def.cpuLimit()));
        if (def.memoryLimit() != null) limits.put("memory", new Quantity(def.memoryLimit()));

        // Always create resources block (even if empty) for consistent API contract
        containerBuilder.withNewResources()
            .withRequests(requests.isEmpty() ? null : requests)
            .withLimits(limits.isEmpty() ? null : limits)
            .endResources();

        JobBuilder builder = new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(def.namespace())
                .withLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(def.backoffLimit())
                .withActiveDeadlineSeconds((long) def.timeoutSeconds())
                .withTtlSecondsAfterFinished(def.ttlAfterFinished())
                .withNewTemplate()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .withContainers(containerBuilder.build())
                    .endSpec()
                .endTemplate()
            .endSpec();

        if (def.serviceAccount() != null && !def.serviceAccount().isBlank()) {
            builder.editSpec().editTemplate().editSpec()
                .withServiceAccountName(def.serviceAccount())
                .endSpec().endTemplate().endSpec();
        }

        return builder.build();
    }

    private static Job buildFromTemplate(JobDefinition def, String dispatchId,
                                          String caseId, String tenancyId,
                                          String capabilityTag, String idempotency,
                                          String inputDataJson) {
        InputStream is = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(def.template());
        if (is == null) {
            throw new IllegalArgumentException("Template not found on classpath: " + def.template());
        }
        Job job = Serialization.unmarshal(is, Job.class);

        String jobName = generateJobName(def.name());
        job.getMetadata().setName(jobName);
        job.getMetadata().setNamespace(def.namespace());

        Map<String, String> labels = buildLabels(def, dispatchId, capabilityTag, tenancyId);
        Map<String, String> existing = job.getMetadata().getLabels();
        if (existing != null) {
            Map<String, String> merged = new LinkedHashMap<>(existing);
            merged.putAll(labels);
            job.getMetadata().setLabels(merged);
        } else {
            job.getMetadata().setLabels(labels);
        }

        job.getSpec().setBackoffLimit(def.backoffLimit());
        job.getSpec().setActiveDeadlineSeconds((long) def.timeoutSeconds());
        job.getSpec().setTtlSecondsAfterFinished(def.ttlAfterFinished());

        String oldPolicy = job.getSpec().getTemplate().getSpec().getRestartPolicy();
        if (oldPolicy != null && !"Never".equals(oldPolicy)) {
            LOG.warnf("Template '%s' had restartPolicy '%s' — overriding to 'Never'",
                def.template(), oldPolicy);
        }
        job.getSpec().getTemplate().getSpec().setRestartPolicy("Never");

        List<EnvVar> envVars = buildEnvVars(def, caseId, tenancyId, capabilityTag,
            idempotency, inputDataJson);
        Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        List<EnvVar> existing_env = container.getEnv();
        if (existing_env == null) {
            container.setEnv(envVars);
        } else {
            List<EnvVar> merged = new ArrayList<>(existing_env);
            merged.addAll(envVars);
            container.setEnv(merged);
        }

        return job;
    }

    static String generateJobName(String definitionName) {
        String slug = deriveSlug(definitionName);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "casehub-" + slug + "-" + suffix;
    }

    static String deriveSlug(String name) {
        String slug = name.toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        if (slug.length() <= 40) return slug;
        String hash = String.format("%05x", name.hashCode() & 0xFFFFF);
        return slug.substring(0, 35) + hash;
    }

    private static Map<String, String> buildLabels(JobDefinition def, String dispatchId,
                                                    String capabilityTag, String tenancyId) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (def.labels() != null) labels.putAll(def.labels());
        labels.put(K8sWorkerConstants.MANAGED_BY_LABEL, K8sWorkerConstants.MANAGED_BY_VALUE);
        labels.put(K8sWorkerConstants.DISPATCH_ID_LABEL, dispatchId);
        labels.put(K8sWorkerConstants.CAPABILITY_LABEL, capabilityTag);
        labels.put(K8sWorkerConstants.TENANCY_ID_LABEL, tenancyId);
        return labels;
    }

    private static List<EnvVar> buildEnvVars(JobDefinition def, String caseId,
                                              String tenancyId, String capabilityTag,
                                              String idempotency, String inputDataJson) {
        List<EnvVar> envVars = new ArrayList<>();
        if (def.environment() != null) {
            def.environment().forEach((k, v) -> envVars.add(new EnvVar(k, v, null)));
        }
        envVars.add(new EnvVar("CASEHUB_CASE_ID", caseId, null));
        envVars.add(new EnvVar("CASEHUB_TENANCY_ID", tenancyId != null ? tenancyId : "", null));
        envVars.add(new EnvVar("CASEHUB_CAPABILITY", capabilityTag, null));
        envVars.add(new EnvVar("CASEHUB_IDEMPOTENCY", idempotency, null));
        envVars.add(new EnvVar("CASEHUB_INPUT_DATA", inputDataJson, null));
        return envVars;
    }
}
