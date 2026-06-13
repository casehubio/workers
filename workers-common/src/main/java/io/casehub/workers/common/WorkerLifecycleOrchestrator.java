package io.casehub.workers.common;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

@ApplicationScoped
public class WorkerLifecycleOrchestrator {

    private static final Logger LOG = Logger.getLogger(WorkerLifecycleOrchestrator.class);

    @Inject @Any
    Instance<WorkerRuntime> runtimes;

    void onStartup(@Observes @Priority(APPLICATION + 10) StartupEvent ev) {
        if (runtimes == null || runtimes.isUnsatisfied()) {
            LOG.info("No WorkerRuntime beans discovered — no worker modules on classpath");
            return;
        }
        initializeAll(runtimes.stream().toList());
    }

    @PreDestroy
    void onShutdown() {
        if (runtimes == null || runtimes.isUnsatisfied()) {
            return;
        }
        shutdownAll(runtimes.stream().toList());
    }

    void initializeAll(List<WorkerRuntime> workerRuntimes) {
        if (workerRuntimes.isEmpty()) {
            LOG.info("No WorkerRuntime beans discovered — no worker modules on classpath");
            return;
        }
        for (WorkerRuntime runtime : workerRuntimes) {
            try {
                runtime.initialize().await().indefinitely();
                if (runtime.status() == WorkerRuntimeStatus.RUNNING) {
                    LOG.infof("Worker '%s' initialized — capabilities: %s",
                        runtime.workerType(), runtime.capabilities());
                } else {
                    LOG.warnf("Worker '%s' did not reach RUNNING after initialize() — status: %s",
                        runtime.workerType(), runtime.status());
                }
            } catch (Exception e) {
                LOG.warnf("Worker '%s' failed to initialize: %s",
                    runtime.workerType(), e.getMessage());
            }
        }
    }

    void shutdownAll(List<WorkerRuntime> workerRuntimes) {
        for (WorkerRuntime runtime : workerRuntimes) {
            if (runtime.status() == WorkerRuntimeStatus.PENDING) {
                continue;
            }
            try {
                runtime.shutdown().await().indefinitely();
            } catch (Exception e) {
                LOG.warnf("Worker '%s' shutdown failed: %s",
                    runtime.workerType(), e.getMessage());
            }
        }
    }
}
