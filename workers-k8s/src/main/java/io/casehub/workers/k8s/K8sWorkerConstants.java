package io.casehub.workers.k8s;

public final class K8sWorkerConstants {
    public static final String WORKER_TYPE = "k8s";
    public static final String TAG_PREFIX = "k8s:";
    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY_VALUE = "casehub";
    public static final String DISPATCH_ID_LABEL = "casehub.io/dispatch-id";
    public static final String CAPABILITY_LABEL = "casehub.io/capability";
    public static final String TENANCY_ID_LABEL = "casehub.io/tenancy-id";

    private K8sWorkerConstants() {}
}
