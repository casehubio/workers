# CaseHub Workers — Camel Worker Design (Revised v3)

**Date:** 2026-06-08
**Status:** Approved — pending implementation plan
**Revision:** v3 — second review cycle; NC1–NC5 + ND1–ND6 + NM1–NM2 addressed

---

## 1. Overview

`casehub-workers` is a multi-module Maven repo at the Integration tier. It provides `ReactiveWorkerProvisioner` and `WorkerExecutionManager` SPI implementations allowing CaseHub cases to dispatch work to different execution runtimes. Apache Camel (300+ connectors) is the first non-trivial implementation.

This spec covers:
- **`workers-common`** — general worker infrastructure shared by all worker implementations. Future migration target: `casehub-engine`, alongside Drools and Flow.
- **`workers-camel`** — Apache Camel adapter implementing both `ReactiveWorkerProvisioner` and `WorkerExecutionManager`.

### Endpoint registry

`casehub-endpoints` is being designed separately in `casehubio/platform` (platform#73). Until then, `casehub.workers.camel.capabilities.<tag>` accepts any full Camel URI (e.g. `kafka:my-topic?brokers=localhost:9092`) as inline connection config. When `EndpointRegistry` ships, `CamelWorkerRoute` SPI beans can inject it to resolve endpoint names at route-build time.

---

## 2. Engine Integration

Two distinct engine call sites. The spec must satisfy both correctly.

### 2.1 ReactiveWorkerProvisioner — capability probe

Called from `CaseContextChangedEventHandler.tryProvision()` when no pre-defined case-definition worker matches a required capability:

1. Engine calls `getCapabilities()` — checks provisioner's supported set.
2. If the needed capability is present, calls `provision(caps, provisionContext)`.
3. After provision returns, fires `CaseLifecycleEvent("WorkerStarted")` — notification only.
4. Does **not** fire `WorkflowExecutionCompleted` here. No work dispatched yet.

For Camel: `provision()` validates the route exists and returns `ProvisionResult.empty()`. Routes are always-running; nothing to spin up.

### 2.2 WorkerExecutionManager — work dispatch and completion

Called from `WorkerScheduleEventHandler` for every work dispatch:

1. Computes `inputDataHash = WorkerExecutionKeys.inputDataHash(caseId, workerName, capabilityName, inputData)` — the idempotency key matching `caseInstance.getWaitingForWorkId()`.
2. Calls `workerExecutionManager.submit(eventLogId, instance, worker, capability, inputData)`.
3. The execution manager fires `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED` when done, or `WorkflowExecutionFailed` on `WORKFLOW_EXECUTION_FAILED` on fault.
4. `WorkflowExecutionCompletedHandler` and `PlanItemCompletionHandler` both consume `WORKER_EXECUTION_FINISHED` (see Section 4.2 — must use `publish()`, not `request()`).

### 2.3 CDI resolution

`NoOpReactiveWorkerProvisioner` and `NoOpWorkerExecutionManager` are `@DefaultBean @ApplicationScoped`. Camel implementations must be plain `@ApplicationScoped` (no `@DefaultBean`) — CDI displaces defaults by classpath presence. Identical pattern to claudony.

### 2.4 Module dependencies

`workers-common` depends on:
- `casehub-engine-api` — `ReactiveWorkerProvisioner`, `ReactiveWorkerStatusListener`, `ProvisionResult`, `ProvisionContext`, `WorkResult`, `Worker`, `Capability`
- `casehub-engine-common` — `WorkerExecutionManager`, `WorkflowExecutionCompleted`, `WorkflowExecutionFailed`, `CaseInstance`, `EventBusAddresses`, `WorkerExecutionKeys`

Same pattern as claudony. `casehub-engine-common` is an internal module explicitly consumed by integration-tier repos.

---

## 3. Module Structure

```
casehub-workers/
  workers-common/         ← must be first in parent POM <modules>
  workers-http/
  workers-camel/
  workers-testing/
```

`workers-common` must be first in the parent POM `<modules>` list — all other modules depend on it.

---

## 4. `workers-common`

### 4.1 Core types

**`WorkerCorrelationContext`** — carries data needed to fire `WorkflowExecutionCompleted` at async completion time:

```java
record WorkerCorrelationContext(
    CaseInstance caseInstance,   // needed for WorkflowExecutionCompleted; see mutable-state note
    Worker worker,               // needed for WorkflowExecutionCompleted
    String idempotency,          // WorkerExecutionKeys.inputDataHash — matches waitingForWorkId
    String tenancyId
) {}
```

**Mutable CaseInstance note:** `CaseInstance` is mutable — the engine calls `setState()` and `setWaitingForWorkId()` on it during normal operation. The registry holds this reference for up to `casehub.workers.async.timeout-minutes` (default: 60 minutes). If the case completes, cancels, or changes state while the Camel route is in flight, `resumeIfWaiting()` inside `WorkflowExecutionCompletedHandler` checks `caseInstance.getState() == WAITING` before resuming — it will silently no-op if the case is already in a terminal state. The stale-reference risk is acknowledged as a known constraint consistent with claudony; it is more significant at 60-minute TTLs. Future mitigation: load a fresh `CaseInstance` from `CrossTenantCaseInstanceRepository` at completion time instead of storing the reference — deferred to when the multi-node registry ships.

**`PendingCompletion`** — one entry per registered async dispatch. The registry generates `dispatchId`:

```java
record PendingCompletion(
    String dispatchId,              // UUID generated by registry — registry key AND casehub-worker-id header value
    WorkerCorrelationContext correlationContext,
    String callbackToken,           // UUID for REST callback auth; generated by registry
    Capability capability,          // needed to construct WorkflowExecutionFailed on timeout/fault
    Long eventLogId,                // needed for WORKER_EXECUTION_FAILED event log entry
    Instant registeredAt,
    Instant expiresAt,
    Map<String, String> provisionerMeta
) {}
```

`dispatchId` is the stable key for all lookup operations — it is unique per dispatch and avoids the collision that would occur if `worker.getName()` were used as the key (two concurrent dispatches of the same capability for different cases would collide).

**`CompletionExpiredEvent`** — CDI event fired when a pending completion TTL expires. Workers-camel observes this to initiate the fault path without `workers-common` needing to know about the engine event bus:

```java
record CompletionExpiredEvent(PendingCompletion pending) {}
```

**`WorkerCompletionPayload`** — JSON body for `POST /workers/complete/{dispatchId}`:

```java
record WorkerCompletionPayload(
    Map<String, Object> output,   // result data; empty map acceptable
    boolean faulted,              // true = faulted; false = completed
    String errorMessage           // nullable; informational when faulted=true
) {}
```

`errorMessage` is informational only — it is not written to the case context. It enables external systems to include a failure reason for observability.

**`CasehubWorkerHeaders`** — header/key name constants shared across all worker types:

```java
public final class CasehubWorkerHeaders {
    public static final String WORKER_ID        = "casehub-worker-id";   // value = dispatchId UUID
    public static final String IDEMPOTENCY      = "casehub-idempotency";
    public static final String CASE_ID          = "casehub-case-id";
    public static final String TENANCY_ID       = "casehub-tenancy-id";
    public static final String TASK_TYPE        = "casehub-task-type";
    public static final String CALLBACK_TOKEN   = "casehub-callback-token";
    public static final String WORK_STATUS      = "casehub-work-status";  // FAULTED override
}
```

`casehub-worker-id` carries the `dispatchId` UUID — not `worker.getName()`. External systems that need to call back use this header value as the `{dispatchId}` path parameter in `POST /workers/complete/{dispatchId}`.

**`WorkerProvisioningException`**:

```java
public class WorkerProvisioningException extends RuntimeException {
    private final String capability;
    public static WorkerProvisioningException noRouteFound(String capability) { ... }
    public static WorkerProvisioningException startupFailed(String capability, Throwable cause) { ... }
}
```

**`WorkerCapabilityResolver<T>`** — plain interface (not `@FunctionalInterface`). Implementations must follow the three-tier chain: SPI → Config → Convention. `@FunctionalInterface` was incorrect — the resolver is a multi-step class, not a lambda:

```java
public interface WorkerCapabilityResolver<T> {
    /**
     * Resolve the dispatch target for a capability tag.
     * Implementations MUST attempt resolution: SPI (highest) → Config → Convention (lowest).
     * Throws WorkerProvisioningException if no mapping found.
     */
    T resolve(String capabilityTag);
    Set<String> capabilities();   // union of all three tiers, computed at startup
    Optional<String> firstMatch(Set<String> candidates); // first capability in candidates that this resolver knows
}
```

### 4.2 Services

**`WorkflowCompletionPublisher`** — fires on `WORKER_EXECUTION_FINISHED` event bus using `publish()` (not `request()`). `WORKER_EXECUTION_FINISHED` has two consumers — `WorkflowExecutionCompletedHandler` and `PlanItemCompletionHandler` — and `publish()` delivers to all. Using `request()` would deliver to exactly one consumer (point-to-point), causing `PlanItemCompletionHandler` to miss completions in blackboard-enabled deployments. This is the same bug documented in the engine's session diary (2026-04-22: `CaseStartedEventHandler` used `request()` and caused rotating consumer delivery):

```java
@ApplicationScoped
public class WorkflowCompletionPublisher {
    @Inject EventBus eventBus;

    public void complete(WorkerCorrelationContext ctx, Map<String, Object> output) {
        eventBus.publish(EventBusAddresses.WORKER_EXECUTION_FINISHED,
            WorkflowExecutionCompleted.approved(
                ctx.caseInstance(), ctx.worker(), ctx.idempotency(), output));
    }

    public void fail(PendingCompletion pending, Throwable cause) {
        eventBus.publish(EventBusAddresses.WORKFLOW_EXECUTION_FAILED,
            new WorkflowExecutionFailed(
                pending.correlationContext().caseInstance(),
                pending.correlationContext().worker(),
                pending.capability(),
                pending.correlationContext().idempotency(),
                pending.eventLogId().toString(),
                cause));
    }
}
```

Return type is `void` — `eventBus.publish()` is fire-and-forget. The event is enqueued in Vert.x's event bus and delivered to all consumers asynchronously. This is the correct semantic: the publisher does not wait for consumers to finish processing.

**`WorkerStatusPublisher`** — lifecycle notifications. Method names mirror `ReactiveWorkerStatusListener` exactly:

```java
@ApplicationScoped
public class WorkerStatusPublisher {
    @Inject ReactiveWorkerStatusListener reactiveWorkerStatusListener;

    public Uni<Void> onWorkerStarted(String dispatchId, Map<String, String> sessionMeta) { ... }
    public Uni<Void> onWorkerCompleted(String dispatchId, WorkResult result) { ... }
    public Uni<Void> onWorkerStalled(String dispatchId) { ... }
}
```

`WorkerStatusPublisher` is for observability notifications. It is NOT the case resumption mechanism. `WorkflowCompletionPublisher` is what resumes the case.

**`WorkerProvisionerSupport`**:

- `validateCapabilities(Set<String> requested, Set<String> supported)` — throws `WorkerProvisioningException` if **any** capability in `requested` is absent from `supported`. Void return — it is a strict all-or-nothing guard. **Not appropriate for `provision()`** where the engine passes all capabilities and the provisioner handles a subset. Use `resolver.firstMatch(capabilities)` in provision() instead.
- `tenancyId(ProvisionContext ctx)` — extracts tenancyId.
- `wrap(Throwable t, String capability)` — converts to `WorkerProvisioningException.startupFailed()`.

**`AsyncWorkerCompletionRegistry`** — generates `dispatchId` and `callbackToken` at registration time:

```java
@ApplicationScoped
public class AsyncWorkerCompletionRegistry {
    @Inject Event<CompletionExpiredEvent> expiryEvents;

    /**
     * Register an async dispatch. Registry generates dispatchId and callbackToken.
     * Returns PendingCompletion with these generated values.
     */
    PendingCompletion register(WorkerCorrelationContext ctx, Capability capability,
                               Long eventLogId, Duration ttl, Map<String, String> provisionerMeta);

    /** Remove and return; empty if dispatchId unknown or already completed. */
    Optional<PendingCompletion> complete(String dispatchId);

    /** Count active dispatches for a given worker name (for getActiveWorkCount). */
    int countByWorkerName(String workerName);

    /** Scheduled: removes expired entries and fires CompletionExpiredEvent per entry. */
    void expireStale();
}
```

TTL: `casehub.workers.async.timeout-minutes` (default: 60). Expiry fires `CompletionExpiredEvent` (CDI async) — workers-camel observes this to fire `WorkflowExecutionFailed`. `workers-common` does not know about the engine event bus.

**Deployment constraint — sticky routing:** The registry is JVM-local. `POST /workers/complete/{dispatchId}` must reach the same JVM that registered the completion. Multi-node deployments require sticky load balancing keyed on `dispatchId`, or a distributed registry implementation. This is a deployment constraint, documented here and in operator docs.

**`WorkerCallbackResource`** — REST endpoint shared by all worker types. Path parameter is `dispatchId` (the UUID from `PendingCompletion`):

```
POST /workers/complete/{dispatchId}
Headers: X-Casehub-Callback-Token: <token>
Body: WorkerCompletionPayload
```

Validates `X-Casehub-Callback-Token` against stored `callbackToken` using constant-time comparison (`MessageDigest.isEqual`). Calls `AsyncWorkerCompletionRegistry.complete(dispatchId)`. If present: calls `WorkflowCompletionPublisher.complete()` (success) or `WorkflowCompletionPublisher.fail()` (faulted). Returns 200 idempotently (returns 200 even if `dispatchId` already resolved). Returns 404 only if `dispatchId` was never registered. Returns 401 on missing or mismatched token.

`WorkerCallbackResource` runs on a Vert.x IO thread. All operations it performs are non-blocking: `ConcurrentHashMap` lookup in the registry + `eventBus.publish()`. No `@Blocking` annotation required.

---

## 5. `workers-camel`

Depends on `workers-common`. Implements `ReactiveWorkerProvisioner`, `WorkerExecutionManager`, and `CamelWorkerFailureHandler`.

### 5.1 Capability-to-route resolution — `CamelCapabilityResolver`

Implements `WorkerCapabilityResolver<String>`. Three-tier resolution:

**Priority 1 — SPI (highest):** CDI-discovered `CamelWorkerRoute @ApplicationScoped` beans:

```java
public interface CamelWorkerRoute {
    Set<String> getCapabilities();
    String getEntryUri();
    ExchangePattern exchangePattern();  // AUTHORITATIVE — mismatch vs Camel route is startup error
}
```

`exchangePattern()` is authoritative. On startup, `CamelWorkerExecutionManager` validates that the registered Camel route's `ExchangePattern` matches the declared value. A mismatch throws `IllegalStateException` with the route ID, declared pattern, and observed pattern. This validation applies only to SPI-registered routes — convention and config routes have no `CamelWorkerRoute` bean, so their exchange pattern is read directly from the Camel route's `ExchangePattern` as registered in `CamelContext`.

**Priority 2 — Config:** `casehub.workers.camel.capabilities.<tag> = <camelUri>`. Any full Camel URI is valid: `direct:my-route`, `kafka:my-topic?brokers=localhost:9092`, etc. Exchange pattern inferred from the route's observed `ExchangePattern` in `CamelContext`.

**Priority 3 — Convention (lowest):** Both conditions must hold simultaneously:
1. A route with ID exactly equal to the capability tag exists in `CamelContext`.
2. The route's first `from:` URI is `direct:{capabilityTag}`.

Neither condition alone is sufficient. A route with matching ID but a different entry URI does not satisfy convention and falls through to `WorkerProvisioningException`.

**`firstMatch(Set<String>)`** — returns the first capability in the set (by iteration order) that has a registered route in any tier. Used by `provision()` to handle partial overlaps.

**Multi-capability dispatch:** One route per dispatch. `firstMatch()` picks the first resolvable capability. The engine schedules capabilities independently.

**Startup ordering:** Capabilities and exchange patterns are computed in a `@Observes @Priority(APPLICATION) StartupEvent` handler — after Camel routes are registered in `CamelContext`. `@PostConstruct` alone fires before Camel routes are ready. The computed capability set is cached; `getCapabilities()` on `ReactiveWorkerProvisioner` reads the cache.

### 5.2 Route definition

YAML DSL and programmatic `RouteBuilder` CDI beans are both supported. Quarkus Camel loads both into the same `CamelContext`.

YAML (convention-based — route ID = capability tag, entry URI = `direct:{capabilityTag}`):
```yaml
- route:
    id: lead-enrichment
    from:
      uri: direct:lead-enrichment
    steps:
      - to:
          uri: salesforce:Lead?...
      - to:
          uri: casehub:complete
```

Programmatic (SPI-based — can inject `EndpointRegistry` once platform#73 ships):
```java
@ApplicationScoped
public class LeadEnrichmentRoute extends RouteBuilder implements CamelWorkerRoute {
    @Override
    public void configure() {
        from("direct:lead-enrichment")
            .to("salesforce:Lead?...")
            .to("casehub:complete");
    }
    @Override public Set<String> getCapabilities()    { return Set.of("lead-enrichment"); }
    @Override public String getEntryUri()              { return "direct:lead-enrichment"; }
    @Override public ExchangePattern exchangePattern() { return ExchangePattern.InOnly; }
}
```

### 5.3 `CamelReactiveWorkerProvisioner`

```java
@ApplicationScoped
public class CamelReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        // Engine passes all capabilities from the case definition. Use firstMatch —
        // validateCapabilities() would throw if any capability is unsupported, but the
        // provisioner is expected to handle a subset only.
        String capability = camelCapabilityResolver.firstMatch(capabilities)
            .orElseThrow(() -> WorkerProvisioningException.noRouteFound(capabilities.toString()));
        camelCapabilityResolver.resolve(capability); // validates route exists; throws if not
        return Uni.createFrom().item(ProvisionResult.empty());
    }

    @Override
    public Uni<Void> terminate(String workerId) {
        return Uni.createFrom().voidItem(); // Camel routes are always-running; no per-worker teardown
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(camelCapabilityResolver.capabilities());
    }
}
```

### 5.4 `CamelWorkerExecutionManager`

```java
@ApplicationScoped
public class CamelWorkerExecutionManager implements WorkerExecutionManager {

    @Observes @Priority(APPLICATION)
    void onStartup(StartupEvent ev) {
        camelCapabilityResolver.initialize();      // compute and cache capability set
        validateSpiRoutePatternConsistency();       // SPI routes only — see Section 5.1
    }

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                            Capability capability, Map<String, Object> inputData) {
        String entryUri = camelCapabilityResolver.resolve(capability.getName());
        if (entryUri == null) {
            // Route was removed after startup — operator error. Log and return.
            LOG.errorf("Camel route for capability %s not found at dispatch time — dropping",
                capability.getName());
            return Uni.createFrom().voidItem();
        }

        String idempotency = WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.getName(), capability.getName(), inputData);

        WorkerCorrelationContext ctx = new WorkerCorrelationContext(
            instance, worker, idempotency, instance.tenancyId);

        ExchangePattern pattern = camelCapabilityResolver.exchangePattern(capability.getName());
        return pattern == ExchangePattern.InOut
            ? submitSync(ctx, entryUri, capability, inputData, eventLogId)
            : submitAsync(ctx, entryUri, capability, eventLogId, inputData);
    }

    @Override
    public Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
        return Uni.createFrom().voidItem(); // Camel workers have no Quartz persisted events
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return asyncWorkerCompletionRegistry.countByWorkerName(workerId);
    }
}
```

Route not found at `submit()` time (route removed after startup): log ERROR, return `Uni.voidItem()`. This is an operator error — no `WorkflowExecutionFailed` is fired because there is nothing to retry. The case will eventually time out or be handled by the engine's watchdog.

### 5.5 Sync path (ExchangePattern.InOut)

```java
private Uni<Void> submitSync(WorkerCorrelationContext ctx, String entryUri,
                              Capability capability, Map<String, Object> inputData,
                              Long eventLogId) {
    return Uni.createFrom()
        .item(() -> buildExchange(ctx, capability, inputData))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .flatMap(exchange -> {
            Exchange response = producerTemplate.request(entryUri, exchange);
            boolean faulted = response.getException() != null
                || "FAULTED".equals(response.getIn().getHeader(CasehubWorkerHeaders.WORK_STATUS));

            if (faulted) {
                completionPublisher.fail(buildPendingForFault(ctx, capability, eventLogId),
                                         response.getException());
                return Uni.createFrom().voidItem();
            }
            completionPublisher.complete(ctx, extractOutput(response));
            return Uni.createFrom().voidItem();
        })
        .onFailure().call(t -> {
            completionPublisher.fail(buildPendingForFault(ctx, capability, eventLogId), t);
            return Uni.createFrom().voidItem();
        });
}
```

Sync faults fire `WorkflowExecutionFailed` (via `completionPublisher.fail()`). `WorkflowExecutionFailed` is then handled by `CamelWorkerFailureHandler` for retry (Section 5.7). `submit()` itself returns `Uni.voidItem()` regardless — the failure is handled asynchronously via the event bus.

### 5.6 Async path (ExchangePattern.InOnly)

```java
private Uni<Void> submitAsync(WorkerCorrelationContext ctx, String entryUri,
                               Capability capability, Long eventLogId,
                               Map<String, Object> inputData) {
    PendingCompletion pending = asyncWorkerCompletionRegistry.register(
        ctx, capability, eventLogId,
        Duration.ofMinutes(asyncTimeoutMinutes), Map.of());

    Exchange exchange = buildExchange(ctx, capability, inputData);
    // dispatchId is the casehub-worker-id — external systems include it in their callback URL
    exchange.getIn().setHeader(CasehubWorkerHeaders.WORKER_ID, pending.dispatchId());
    exchange.getIn().setHeader(CasehubWorkerHeaders.CALLBACK_TOKEN, pending.callbackToken());

    return Uni.createFrom().voidItem()
        .invoke(() -> producerTemplate.send(entryUri, exchange));
    // Returns immediately. Completion via casehub:complete or POST /workers/complete/{dispatchId}
}
```

### 5.7 Exchange input mapping

| Header (`CasehubWorkerHeaders`) | Value |
|---|---|
| `casehub-worker-id` | `pending.dispatchId()` — per-dispatch UUID; NOT `worker.getName()` |
| `casehub-idempotency` | `ctx.idempotency()` — `WorkerExecutionKeys.inputDataHash(...)` |
| `casehub-case-id` | `instance.getUuid().toString()` |
| `casehub-tenancy-id` | `instance.tenancyId` |
| `casehub-task-type` | `capability.getName()` |
| `casehub-callback-token` | `pending.callbackToken()` (async only) |

Body: `inputData` serialised as JSON.

### 5.8 `CasehubCamelComponent` — `casehub:complete`

Camel `Processor.process(Exchange)` is synchronous. Camel component processor threads are standard Java threads (Camel uses its own thread pool, not Vert.x IO threads). Calling blocking operations from `process()` is safe.

`WorkflowCompletionPublisher.complete/fail()` call `eventBus.publish()` which is non-blocking from Vert.x's perspective — it enqueues the event and returns immediately. The processor blocks briefly on this enqueue and then returns. The Vert.x event bus consumers (`WorkflowExecutionCompletedHandler`, `PlanItemCompletionHandler`, `CamelWorkerFailureHandler`) process the event asynchronously after the processor returns. This is the correct semantic — the Camel exchange completes and the engine resumes the case asynchronously.

```java
@Override
public void process(Exchange exchange) throws Exception {
    String dispatchId = exchange.getIn().getHeader(CasehubWorkerHeaders.WORKER_ID, String.class);
    if (dispatchId == null) {
        throw new IllegalStateException(
            "casehub-worker-id header missing — route must stamp it from the incoming exchange");
    }

    boolean faulted = exchange.getException() != null
        || "FAULTED".equals(exchange.getIn().getHeader(CasehubWorkerHeaders.WORK_STATUS));

    Optional<PendingCompletion> pending = asyncWorkerCompletionRegistry.complete(dispatchId);
    if (pending.isEmpty()) {
        LOG.warnf("casehub:complete — dispatchId %s not in registry (already completed or expired)", dispatchId);
        return;
    }

    if (faulted) {
        completionPublisher.fail(pending.get(), exchange.getException());
    } else {
        Map<String, Object> output = exchange.getIn().getBody(Map.class);
        if (output == null) output = Map.of();
        completionPublisher.complete(pending.get().correlationContext(), output);
    }
    // publish() is non-blocking; returns without waiting for consumers to process the event
}
```

Body contract for route authors: set exchange body to `Map<String, Object>` before routing to `casehub:complete`. Non-Map body (String, POJO, null) → treated as empty output `Map.of()`.

### 5.9 Fault path and retry — `CamelWorkerFailureHandler`

The fault path for Camel workers mirrors `QuartzWorkerExecutionJobListener`'s role for Quartz workers. `CamelWorkerFailureHandler` in `workers-camel` handles both:
- Explicit faults (sync exception or FAULTED status in `casehub:complete`)
- Timeout faults (async expiry via `CompletionExpiredEvent` CDI event)

```java
@ApplicationScoped
public class CamelWorkerFailureHandler {

    /** Handle faults from sync and async-explicit paths. */
    @ConsumeEvent(value = EventBusAddresses.WORKFLOW_EXECUTION_FAILED, blocking = true)
    public Uni<Void> onFault(WorkflowExecutionFailed event) {
        // Only handle capabilities this Camel manager knows about
        if (!camelCapabilityResolver.capabilities().contains(event.capability().getName())) {
            return Uni.createFrom().voidItem();
        }
        return handleFailure(event.caseInstance(), event.worker(), event.capability(),
                             event.inputDataHash(), event.eventLogId(), event.cause());
    }

    /** Handle async timeout via CDI event from AsyncWorkerCompletionRegistry.expireStale(). */
    void onExpiry(@ObservesAsync CompletionExpiredEvent event) {
        PendingCompletion pending = event.pending();
        handleFailure(
            pending.correlationContext().caseInstance(),
            pending.correlationContext().worker(),
            pending.capability(),
            pending.correlationContext().idempotency(),
            pending.eventLogId().toString(),
            null /* no cause for timeout */)
        .subscribe().with(ignored -> {}, err -> LOG.errorf(err, "Expiry fault handling failed"));
    }

    private Uni<Void> handleFailure(CaseInstance instance, Worker worker, Capability capability,
                                     String inputDataHash, String eventLogId, Throwable cause) {
        return eventLogRepository
            .persistWorkerExecutionFailed(instance, worker, inputDataHash, instance.tenancyId)
            .flatMap(ignored -> countFailures(instance.getUuid(), worker.getName(), inputDataHash))
            .flatMap(failureCount -> {
                int maxRetries = worker.getExecutionPolicy().maxRetries();
                if (failureCount <= maxRetries) {
                    // Retry: reload inputData from event log, re-dispatch
                    return reloadAndResubmit(instance, worker, capability, eventLogId);
                } else {
                    // Exhausted: publish WORKER_RETRIES_EXHAUSTED
                    eventBus.publish(EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
                        new WorkerRetriesExhaustedEvent(
                            instance.getUuid(), worker.getName(), inputDataHash));
                    return Uni.createFrom().voidItem();
                }
            });
    }

    private Uni<Void> reloadAndResubmit(CaseInstance instance, Worker worker,
                                          Capability capability, String eventLogId) {
        // Load inputData from the scheduled event log entry (same approach as QuartzWorkerExecutionJob)
        return eventLogRepository.findById(Long.parseLong(eventLogId), instance.tenancyId)
            .flatMap(eventLog -> {
                Map<String, Object> inputData = objectMapper.convertValue(eventLog.getPayload(), MAP_TYPE);
                return workerExecutionManager.submit(
                    Long.parseLong(eventLogId), instance, worker, capability, inputData);
            });
    }
}
```

**Retry count source:** query `WORKER_EXECUTION_FAILED` event log entries for `(caseId, workerName, inputDataHash)`. Same approach as Quartz — the event log is the source of truth for retry count.

**Retry re-dispatch:** loads `inputData` from the original event log entry (same as `QuartzWorkerExecutionJob.execute()`), then re-calls `submit()`. The new dispatch generates a fresh `dispatchId` and `callbackToken`.

---

## 6. `workers-testing`

```
MockAsyncWorkerCompletionRegistry    — captures registrations; exposes triggerCompletion(dispatchId, output) and triggerFault(dispatchId, cause)
CapturingWorkerStatusPublisher       — records all onWorkerStarted/Completed/Stalled calls
TestCamelWorkerRoute                 — sample CamelWorkerRoute SPI impl; exchange pattern configurable
WorkflowCompletionCaptor             — captures WorkflowExecutionCompleted events on event bus
WorkflowFailureCaptor                — captures WorkflowExecutionFailed events on event bus
WorkerTestSupport                    — static helpers: correlationContext(instance, worker), completedPayload(output), faultedPayload(msg)
```

Never compile or runtime dependency — test scope only.

---

## 7. Test Coverage

### `workers-common`

- `AsyncWorkerCompletionRegistry`: `register()` → unique `dispatchId` per call; two concurrent `register()` calls for same `worker.getName()` → different `dispatchId` keys, both resolvable
- `complete(dispatchId)` → success; second `complete()` → empty (idempotent)
- Wrong `callbackToken` → rejected before registry lookup (401 path in `WorkerCallbackResource`)
- `expireStale()` → fires `CompletionExpiredEvent` CDI event per expired entry
- `WorkerCallbackResource`: 200 on valid token; 401 on wrong token; 404 on unknown `dispatchId`; 200 idempotent on second call
- `WorkflowCompletionPublisher`: verify `eventBus.publish()` (not `request()`) on `WORKER_EXECUTION_FINISHED`; verify both `WorkflowExecutionCompletedHandler` and `PlanItemCompletionHandler` receive the event in a `@QuarkusTest`

### `workers-camel`

- `CamelWorkerExecutionManager.submit()`: sync path — `WorkflowCompletionCaptor` asserts event fired with correct `idempotency`, `caseInstance`, `worker`, `output`; `casehub-worker-id` header = `dispatchId` UUID (not `worker.getName()`)
- Sync fault: `WorkflowFailureCaptor` asserts `WorkflowExecutionFailed` fired on `WORKFLOW_EXECUTION_FAILED`
- Async Path A: `PendingCompletion` registered; `submit()` returns immediately; `casehub:complete` fires `WorkflowExecutionCompleted`
- Async Path B: `POST /workers/complete/{dispatchId}` with valid token fires `WorkflowExecutionCompleted`; wrong token → 401
- Async fault via `casehub:complete` with FAULTED header: `WorkflowExecutionFailed` fired
- Async expiry: `CompletionExpiredEvent` → `CamelWorkerFailureHandler.onExpiry()` → `WorkflowExecutionFailed`
- `CamelWorkerFailureHandler`: retry below limit → `submit()` called again; exhausted → `WORKER_RETRIES_EXHAUSTED` published
- Startup validation: `exchangePattern()` mismatch → `IllegalStateException` at startup
- Convention: both conditions required (route ID AND `direct:{tag}` URI)
- Config: full Camel URI in property overrides convention
- SPI: `CamelWorkerRoute` bean overrides config
- `schedulePersistedEvent()`: no-op, returns `Uni.voidItem()`

---

## 8. Remaining open questions

- `workers-common` migration to `casehub-engine` alongside Drools and Flow — deferred
- `EndpointRegistry` integration (platform#73) — deferred
- Distributed `AsyncWorkerCompletionRegistry` for multi-node — deferred; current in-memory registry requires sticky routing
- Quartz and Camel retry count logic are now parallel implementations — consider extracting retry-count query and `WORKER_RETRIES_EXHAUSTED` logic to a shared utility in `casehub-engine-common` in a follow-on issue
- Stale `CaseInstance` reference in long-lived registry — potential future improvement: reload fresh instance from `CrossTenantCaseInstanceRepository` at completion time; deferred to multi-node registry work
