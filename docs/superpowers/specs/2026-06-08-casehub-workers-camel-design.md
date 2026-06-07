# CaseHub Workers — Camel Worker Design

**Date:** 2026-06-08
**Status:** Approved — pending implementation plan
**Scope:** `workers-common` general infrastructure + `workers-camel` Camel adapter

---

## 1. Overview

`casehub-workers` is a multi-module Maven repo at the Integration tier of the CaseHub platform. It provides thin `WorkerProvisioner` SPI implementations that allow CaseHub cases to dispatch work to different execution runtimes — HTTP endpoints, Apache Camel routes, shell scripts, Kubernetes Jobs, and more.

This spec covers two modules:

- **`workers-common`** — general async worker infrastructure shared by all worker types (sync and async). Future migration target: `casehub-engine`, alongside Drools and Flow worker infrastructure.
- **`workers-camel`** — Apache Camel adapter. Implements `WorkerProvisioner` via Camel routes, supporting 300+ connectors (Kafka, AWS, Salesforce, SAP, FTP, databases) as CaseHub case step executors.

**Endpoint registry:** `casehub-endpoints` is being designed separately in `casehubio/platform` (platform#73). Workers reference named endpoints rather than hardcoded connection details. The Camel worker is designed to accept both an endpoint name (resolved via `EndpointRegistry`) and inline connection config, so it works before platform ships the registry.

---

## 2. Module Structure

```
casehub-workers/
  workers-common/         General worker infrastructure — sync and async
  workers-camel/          Apache Camel WorkerProvisioner adapter
  workers-http/           HTTP/webhook WorkerProvisioner (separate spec)
  workers-testing/        Shared test fixtures — never compile/runtime dep
```

The parent POM needs a `workers-common` module added (current skeleton has http, camel, testing only).

---

## 3. `workers-common` — General Worker Infrastructure

Shared by all worker types regardless of execution runtime or completion pattern.

### 3.1 Types

**`WorkerCorrelationContext`** — carries correlation data through async operations:
```java
record WorkerCorrelationContext(
    String workerId,
    String correlationKey,
    UUID caseId,
    String tenancyId
) {}
```

**`PendingCompletion`** — one entry per registered async worker:
```java
record PendingCompletion(
    WorkerCorrelationContext correlationContext,
    Instant registeredAt,
    Instant expiresAt,
    Map<String, String> provisionerMeta   // provisioner-specific metadata, opaque to registry
) {}
```

**`WorkerCompletionPayload`** — JSON body for `POST /workers/complete/{workerId}` (shared format for all external callbacks — HTTP workers, Lambda, K8s Job, Ansible):
```java
record WorkerCompletionPayload(
    WorkStatus status,                    // COMPLETED or FAULTED
    Map<String, Object> output,           // result data; empty map if faulted
    String errorMessage                   // nullable; populated on FAULTED
) {}
```

**`CasehubWorkerHeaders`** — header/key name constants (each worker maps these to its native format — Camel exchange headers, HTTP request headers, environment variables):
```java
public final class CasehubWorkerHeaders {
    public static final String WORKER_ID       = "casehub-worker-id";
    public static final String CORRELATION_KEY = "casehub-correlation-key";
    public static final String CASE_ID         = "casehub-case-id";
    public static final String TENANCY_ID      = "casehub-tenancy-id";
    public static final String TASK_TYPE       = "casehub-task-type";
    public static final String WORK_STATUS     = "casehub-work-status";  // override on fault
}
```

**`WorkerProvisioningException`** — standard exception for all workers, includes capability name:
```java
public class WorkerProvisioningException extends RuntimeException {
    private final String capability;
    // factory: WorkerProvisioningException.noRouteFound(capability)
    // factory: WorkerProvisioningException.startupFailed(capability, cause)
}
```

**`WorkerCapabilityResolver<T>`** — `@FunctionalInterface` documenting the convention → config → SPI pattern that every worker module follows:
```java
@FunctionalInterface
public interface WorkerCapabilityResolver<T> {
    /**
     * Resolve the worker-specific target (route URI, URL, script path, job template)
     * for a capability tag.
     *
     * Implementations MUST follow the resolution chain:
     *   1. SPI  (CDI-discovered beans) — highest priority
     *   2. Config (property overrides)
     *   3. Convention (capability tag → target by naming rule) — lowest priority
     *
     * @throws WorkerProvisioningException if no mapping found
     */
    T resolve(String capabilityTag);
}
```

### 3.2 Services

**`WorkerStatusPublisher`** — CDI helper wrapping `WorkerStatusListener`. Workers call this rather than managing listener injection directly. Handles `@Inject WorkerStatusListener` and the idempotency guard (no-op if already completed):
```java
@ApplicationScoped
public class WorkerStatusPublisher {
    void onStarted(String workerId, Map<String, String> sessionMeta);
    void onCompleted(String workerId, WorkResult result);
    void onStalled(String workerId);
}
```

**`WorkerProvisionerSupport`** — common provisioner boilerplate:
- Capability filtering: `filterCapabilities(Set<String> requested, Set<String> supported)` — validates that requested capabilities are a subset of supported
- Tenancy stamping: extracts `tenancyId` from `ProvisionContext` and adds to `WorkerCorrelationContext`
- Exception wrapping: converts runtime exceptions to `WorkerProvisioningException`

**`SyncCompletionHandler`** — completes a worker inline without registry. Used by sync workers (HTTP sync, Camel InOut) to avoid duplicating the result-mapping + `onCompleted()` call pattern:
```java
@ApplicationScoped
public class SyncCompletionHandler {
    WorkResult complete(String workerId, Map<String, Object> output, UUID caseId);
    WorkResult fault(String workerId, UUID caseId, Throwable cause);
}
```

**`AsyncWorkerCompletionRegistry`** — in-memory store of pending async completions:
```java
@ApplicationScoped
public class AsyncWorkerCompletionRegistry {
    void register(String workerId, PendingCompletion pending);
    Optional<PendingCompletion> complete(String workerId, WorkerCompletionPayload payload);
    void expireStale();      // called on a schedule; fires onWorkerStalled for expired entries
}
```

TTL configured via `casehub.workers.async.timeout-minutes` (default: 60). Expiry fires `WorkerStatusPublisher.onStalled(workerId)` and removes the entry. Duplicate `complete()` calls for the same `workerId` after removal are no-ops (idempotent — `WorkResult.correlationKey` enforces this at the engine level too).

**`WorkerCallbackResource`** — REST endpoint shared by all async workers:
```
POST /workers/complete/{workerId}
Body: WorkerCompletionPayload
```
Looks up `AsyncWorkerCompletionRegistry`, calls `WorkerStatusPublisher.onCompleted()`. Returns 200 (idempotent — succeeds even if registry entry already resolved); 404 if `workerId` was never registered.

---

## 4. `workers-camel` — Apache Camel Adapter

### 4.1 Capability-to-Route Resolution Chain

`CamelCapabilityResolver` implements `WorkerCapabilityResolver<String>` (resolves to Camel route entry URI):

**Priority 1 — SPI (highest):** CDI-discovered `CamelWorkerRoute @ApplicationScoped` beans:
```java
public interface CamelWorkerRoute {
    Set<String> getCapabilities();
    String getEntryUri();           // e.g. "direct:salesforce-enrichment"
    ExchangePattern exchangePattern(); // InOut (sync) or InOnly (async)
}
```
Route authors who need CDI injection in their route implement this SPI and declare a `RouteBuilder` separately.

**Priority 2 — Config:** `casehub.workers.camel.capabilities.<tag> = direct:my-route`. Loaded via `@ConfigMapping` at startup. Exchange pattern detected from the resolved route's registered `ExchangePattern`.

**Priority 3 — Convention (lowest):** If a Camel route with ID equal to the capability tag exists in `CamelContext`, entry URI is `direct:{capabilityTag}`. Zero config — just name the route correctly.

`CamelWorkerProvisioner.getCapabilities()` returns the union of all three sources, computed once at startup via `CamelCapabilityResolver`.

### 4.2 Route Definition

Both YAML DSL and programmatic `RouteBuilder` CDI beans are supported. Quarkus Camel loads both into the same `CamelContext` — the provisioner does not care which mechanism was used. Exchange pattern is declared on the route definition in either case.

YAML example (convention-based, capability tag = route ID):
```yaml
- route:
    id: salesforce-lead-enrichment
    from:
      uri: direct:salesforce-lead-enrichment
    steps:
      - to:
          uri: salesforce:Lead?...
      - to:
          uri: casehub:complete
```

Programmatic example (SPI-based, CDI injection available):
```java
@ApplicationScoped
public class LeadEnrichmentRoute extends RouteBuilder
    implements CamelWorkerRoute {

    @Inject EndpointRegistry endpointRegistry;

    @Override
    public void configure() {
        from("direct:lead-enrichment")
            .process(this::enrichFromRegistry)
            .to("casehub:complete");
    }

    @Override public Set<String> getCapabilities() { return Set.of("lead-enrichment"); }
    @Override public String getEntryUri() { return "direct:lead-enrichment"; }
    @Override public ExchangePattern exchangePattern() { return ExchangePattern.InOnly; }
}
```

### 4.3 Exchange Input Mapping

`CamelExchangeMapper` builds the Camel exchange from `ProvisionContext` using `CasehubWorkerHeaders` constants (defined in `workers-common`):

| Header (from `CasehubWorkerHeaders`) | Source |
|---|---|
| `casehub-worker-id` | generated UUID |
| `casehub-correlation-key` | `WorkResult.correlationKey` |
| `casehub-case-id` | `ProvisionContext.caseId` |
| `casehub-tenancy-id` | `ProvisionContext` → `CurrentPrincipal.tenancyId()` |
| `casehub-task-type` | `ProvisionContext.taskType` |

Body: `WorkerContext` serialised as JSON (nullable — absent if `ProvisionContext.workerContext` is null).

### 4.4 Completion Paths

**Sync (ExchangePattern.InOut):**
```
provision() → ProducerTemplate.request(uri, exchange)   [blocks]
           ← response exchange
           → CamelExchangeMapper maps body → Map<String, Object>
           → SyncCompletionHandler.complete(workerId, output, caseId)
           → WorkerStatusPublisher.onCompleted()
           → return ProvisionResult
```
Route exception → `SyncCompletionHandler.fault()` → `WorkerStatusPublisher.onStalled()` → `WorkerProvisioningException`.

**Async (ExchangePattern.InOnly):**
```
provision() → WorkerCorrelationContext created
           → AsyncWorkerCompletionRegistry.register(workerId, pending)
           → CamelExchangeMapper stamps correlation headers
           → ProducerTemplate.send(uri, exchange)        [fire-and-forget]
           → return ProvisionResult immediately

Later:
  Path A (casehub:complete):
    Route to("casehub:complete")
    CasehubCamelComponent reads casehub-worker-id header
    → AsyncWorkerCompletionRegistry.complete(workerId, payload)
    → WorkerStatusPublisher.onCompleted()

  Path B (REST callback):
    POST /workers/complete/{workerId}  { status, output }
    → AsyncWorkerCompletionRegistry.complete(workerId, payload)
    → WorkerStatusPublisher.onCompleted()
```

### 4.5 `CasehubCamelComponent`

Custom Camel component registered as `casehub:`. `casehub:complete` is the only endpoint path in scope for this spec.

On `process(Exchange)`:
1. Read `casehub-worker-id` header — if absent, throw `IllegalStateException` (route author error)
2. Map exchange body + `casehub-work-status` header → `WorkerCompletionPayload`
3. Call `AsyncWorkerCompletionRegistry.complete(workerId, payload)`

Camel component registration via Quarkus Camel extension (`@RegisterForReflection`, component registered in `META-INF/services/org/apache/camel/component/casehub`).

### 4.6 Configuration

```
casehub.workers.async.timeout-minutes          = 60        # in workers-common
casehub.workers.camel.capabilities.<tag>       = direct:…  # route URI overrides
```

---

## 5. `workers-testing` — Shared Fixtures

All fixtures are usable by `workers-http`, `workers-camel`, `workers-script`, and any future worker module. Never a compile or runtime dependency — test scope only.

| Fixture | Purpose |
|---|---|
| `MockAsyncWorkerCompletionRegistry` | Captures registrations; exposes `triggerCompletion(workerId, payload)` for test control |
| `CapturingWorkerStatusPublisher` | Records all `onStarted/onCompleted/onStalled` calls; provides assertion helpers |
| `TestCamelWorkerRoute` | Sample `CamelWorkerRoute` SPI impl; configurable exchange pattern; used to test both sync and async paths |
| `WorkerTestSupport` | Static helpers: `completedPayload(output)`, `faultedPayload(msg)`, `correlationContext(caseId)` |

---

## 6. Test Coverage

### `workers-common`

- `AsyncWorkerCompletionRegistry`: register → complete (success); register → expire (stalled); duplicate complete (no-op); tenant isolation (different `tenancyId` cannot complete another tenant's worker)
- `WorkerCallbackResource`: 200 on valid completion (idempotent — returns 200 even if registry entry already resolved); 404 only if workerId was never registered (unknown to the registry)
- TTL: expired entry fires `onStalled` via scheduled `expireStale()`

### `workers-camel`

- Sync path: `@QuarkusTest` with real Camel context; route invoked; response body mapped to `WorkResult`; `CapturingWorkerStatusPublisher` asserts `onCompleted` called within `provision()`
- Async Path A: `PendingCompletion` registered; `provision()` returns immediately; `to("casehub:complete")` fires `onCompleted`
- Async Path B: `PendingCompletion` registered; REST `POST /workers/complete/{workerId}` fires `onCompleted`
- Convention resolver: route ID matches capability, resolved with zero config
- Config resolver: property override wins over convention
- SPI resolver: `CamelWorkerRoute` bean wins over config
- Missing capability: `WorkerProvisioningException` with capability name in message
- Header propagation: `casehub-tenancy-id` appears on exchange and in `WorkerCorrelationContext`

---

## 7. Future Work (out of scope for this spec)

- `workers-common` migration to `casehub-engine` alongside Drools and Flow worker infrastructure
- `EndpointRegistry` integration (platform#73) — workers reference named endpoints; current design accepts inline config as fallback
- `workers-http` spec (priority 1 — simpler than Camel, proves `workers-common` contracts)
- Persistent `AsyncWorkerCompletionRegistry` for multi-node deployments (Infinispan or JPA)
- Multi-capability dispatch: a single `provision()` call with multiple capabilities mapped to a Camel pipeline
- `workers-mcp`, `workers-script`, `workers-k8s-job` specs
