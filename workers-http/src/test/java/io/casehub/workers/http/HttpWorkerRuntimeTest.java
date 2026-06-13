package io.casehub.workers.http;

import io.casehub.workers.common.WorkerRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HttpWorkerRuntimeTest {

    @Test
    void initialStatus_isPending() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        assertThat(runtime.status()).isEqualTo(WorkerRuntimeStatus.PENDING);
        assertThat(runtime.workerType()).isEqualTo("http");
    }

    @Test
    void initialize_transitionsToRunning() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        runtime.initialize().await().indefinitely();

        assertThat(runtime.status()).isEqualTo(WorkerRuntimeStatus.RUNNING);
    }

    @Test
    void initialize_delegatesToResolver() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        runtime.initialize().await().indefinitely();

        assertThat(runtime.status()).isEqualTo(WorkerRuntimeStatus.RUNNING);
        assertThat(runtime.capabilities()).isEqualTo(resolver.capabilities());
    }

    @Test
    void capabilities_delegatesToResolver() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        resolver.initialize(List.of(), Map.of(
            "send-email", Map.of("url", "https://mail.example.com/send", "method", "POST"),
            "fetch-data", Map.of("url", "https://api.example.com/data", "method", "GET")
        ), 30);

        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        assertThat(runtime.capabilities()).containsExactlyInAnyOrder("send-email", "fetch-data");
        assertThat(runtime.capabilities()).isEqualTo(resolver.capabilities());
    }

    @Test
    void initialize_whenAlreadyRunning_isNoOp() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        runtime.initialize().await().indefinitely();
        assertThat(runtime.status()).isEqualTo(WorkerRuntimeStatus.RUNNING);

        runtime.initialize().await().indefinitely();
        assertThat(runtime.status()).isEqualTo(WorkerRuntimeStatus.RUNNING);
    }

    @Test
    void shutdown_transitionsToStopped() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        runtime.initialize().await().indefinitely();
        runtime.shutdown().await().indefinitely();

        assertThat(runtime.status()).isEqualTo(WorkerRuntimeStatus.STOPPED);
    }

    @Test
    void capabilities_beforeInit_isEmpty() {
        HttpEndpointResolver resolver = new HttpEndpointResolver();
        HttpWorkerRuntime runtime = new HttpWorkerRuntime(resolver);

        assertThat(runtime.capabilities()).isEmpty();
    }
}
