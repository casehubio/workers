package io.casehub.workers.common;

import io.smallrye.mutiny.Uni;
import java.util.Set;

/**
 * Lifecycle contract for a worker runtime — the infrastructure that executes
 * dispatched work for a specific worker type.
 *
 * <p>A {@code WorkerRuntime} is an <em>executor</em>, not a task instance.
 * It boots, discovers what it can execute, accepts dispatches, and eventually
 * shuts down. Contrast with {@code WorkerStatusListener}, which tracks
 * individual dispatch (task-instance) lifecycle events
 * ({@code onWorkerStarted}, {@code onWorkerCompleted}, {@code onWorkerStalled}).
 *
 * <h3>Relationship to other SPIs</h3>
 * <ul>
 *   <li>{@code ReactiveWorkerProvisioner} — capability probe at case
 *       planning time. Provisioner implementations delegate to their
 *       module's resolver, which is populated during
 *       {@link #initialize()}. The provisioner does not call
 *       {@link #capabilities()} directly.</li>
 *   <li>{@code WorkerExecutionManager} — dispatch at execution time. Uses
 *       the worker module's internal resolver (e.g., {@code McpServerResolver})
 *       to route a capability tag to a concrete target.</li>
 *   <li>{@code WorkerStatusListener} / {@code ReactiveWorkerStatusListener}
 *       — per-dispatch status callbacks. Orthogonal to runtime lifecycle.</li>
 * </ul>
 *
 * <h3>Terminology alignment</h3>
 * <p>The status vocabulary draws from Serverless Workflow 1.0
 * ({@code WorkflowStatus}) and CaseHub's own {@code CaseStatus} /
 * {@code PlanItemStatus}. Where a concept maps directly (PENDING, RUNNING,
 * FAULTED), the same name is used. Where worker runtimes have concerns
 * that task instances do not (capability discovery, connection pooling,
 * session management), worker-specific terms are introduced.
 *
 * <h3>Future lifecycle methods</h3>
 * <p>Methods that may be added as consumers emerge:
 * <ul>
 *   <li>{@code suspend()} / {@code resume()} — RUNNING ↔ SUSPENDED,
 *       for backpressure or maintenance windows.</li>
 *   <li>{@code healthCheck()} — liveness/readiness probe, returning
 *       current status plus diagnostics.</li>
 *   <li>{@code drain()} — stop accepting new dispatches, wait for
 *       in-flight work to complete, then transition to STOPPED.</li>
 * </ul>
 *
 * <h3>Implementation notes</h3>
 * <p>Implementations must be {@code @ApplicationScoped}. The runtime
 * orchestrator discovers all {@code WorkerRuntime} beans via CDI and
 * calls {@link #initialize()} at application startup. Implementations
 * must be safe to call from the Vert.x event loop — avoid blocking
 * operations or use {@code emitOn(Infrastructure.getDefaultWorkerPool())}
 * where necessary.
 */
public interface WorkerRuntime {

    /**
     * Worker type discriminator — e.g., {@code "mcp"}, {@code "http"},
     * {@code "camel"}, {@code "github-actions"}. Must match the value
     * used in {@code PendingCompletion.workerType()} and CDI event
     * filtering.
     */
    String workerType();

    /**
     * Current lifecycle status. Reflects initialization outcome and
     * shutdown state only. Post-initialization failures (server
     * unreachability, connection errors) are handled by the per-dispatch
     * fault pipeline and do not change runtime status. A future
     * {@code healthCheck()} method could surface runtime-level
     * degradation.
     *
     * @see WorkerRuntimeStatus
     */
    WorkerRuntimeStatus status();

    /**
     * Boot the worker runtime: load configuration, establish connections,
     * discover capabilities.
     *
     * <p>Transitions: {@code PENDING → RUNNING} on success,
     * {@code PENDING → FAULTED} on failure. Calling {@code initialize()}
     * on a runtime that is already {@code RUNNING} is a no-op. Calling
     * {@code initialize()} on a {@code FAULTED} runtime retries
     * initialization (enabling recovery without application restart).
     *
     * <p>For workers with external connectivity (e.g., MCP session
     * initialization, remote endpoint health checks), this method
     * performs the initial handshake. Capability discovery (e.g.,
     * MCP {@code tools/list}) also happens inside this method.
     * After {@code initialize()} completes, {@link #capabilities()}
     * returns the full set.
     */
    Uni<Void> initialize();

    /**
     * Release resources held by this worker runtime: close sessions,
     * return connections, cancel timers.
     *
     * <p>Transitions to {@code STOPPED} on completion. Called at
     * application shutdown. Implementations should be best-effort —
     * log failures but do not throw.
     */
    Uni<Void> shutdown();

    /**
     * Returns the set of capability tags this worker can handle.
     *
     * <p>Valid after {@link #initialize()} succeeds. The orchestrator
     * calls this after initialization to log discovered capabilities.
     * Provisioner implementations typically delegate to their module's
     * resolver (e.g., {@code McpServerResolver.capabilities()}) which
     * is populated during {@link #initialize()}.
     *
     * <p>For config-driven workers this returns the statically
     * configured set. For discovery-capable workers (e.g., MCP via
     * {@code tools/list}), this returns dynamically discovered
     * capabilities merged with any config-declared ones.
     *
     * <p>Synchronous — reads from an in-memory map populated during
     * {@link #initialize()}. Consistent with {@link #workerType()} and
     * {@link #status()} which are also synchronous state queries.
     */
    Set<String> capabilities();
}
