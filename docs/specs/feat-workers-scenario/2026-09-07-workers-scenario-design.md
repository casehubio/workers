# workers-scenario Design Spec

**Date:** 2026-09-07
**Branch:** feat/workers-scenario
**Status:** draft

## Overview

New worker module that dispatches CaseHub case steps as scenario executions on casehub-pages instances. The scenario engine in pages is a YAML-driven automation platform that executes ordered steps via browser ARIA automation, GraphQL mutations, and REST calls. This worker integrates it into the CaseHub case lifecycle as another worker backend.

## Context

The scenario engine was built for demos but has evolved into a general automation platform. Teams want to run onboarding workflows, data imports, and environment setup as case steps. The engine supports parameterised scripts, data-driven loops (forEach/when via yaml-core), script composition (call), and a script library with bundled, uploaded, and external registry sources.

A CaseHub case is already a playbook — ordered steps dispatched to workers by capability. The scenario worker adds `scenario:*` capabilities to the mix, enabling playbooks like:

```
step 1: http:provision-environment    → HTTP worker
step 2: scenario:seed-demo-data       → Scenario worker
step 3: scenario:onboard-users        → Scenario worker (CSV forEach)
step 4: script:generate-report        → Script worker
```

Output data flows forward between steps via `WorkflowExecutionCompleted`.

## Module Identity

| Property | Value |
|----------|-------|
| Module | `workers-scenario` |
| Artifact | `casehub-workers-scenario` |
| Root package | `io.casehub.workers.scenario` |
| Worker type | `"scenario"` |
| Capability tag prefix | `scenario:` |
| Fault address | `casehub.workers.scenario.fault` |

## Architecture

### Endpoint Resolution — 3-Tier, Tenant-Aware

Follows the HTTP/MCP pattern. Resolves a capability tag to a pages instance GraphQL endpoint:

- **Tier 1:** SPI beans implementing `ScenarioRoute` (operator-provided, optional)
- **Tier 2:** Config properties — `casehub.workers.scenario.endpoints.<name>.url`
- **Tier 3:** EndpointRegistry — `Path.of("scenario", scriptName)` with tenancyId. Protocol check: `EndpointProtocol.SCENARIO` only.

All workers targeting service instances (not stateless functions) should be tenant-scoped. Pages instances serve specific tenants; the EndpointRegistry handles tenant → platform-global fallback internally.

A single pages instance hosts many scripts, so multiple `scenario:<name>` capability tags may resolve to the same endpoint URL. The script name is extracted from the tag and passed to the pages GraphQL API at dispatch time.

### Dispatch Flow

1. Engine calls `submit(eventLogId, instance, worker, capability, inputData, bindingName)`
2. `ScenarioEndpointResolver` resolves `scenario:<name>` → `ResolvedScenarioEndpoint(url, scriptName)`
3. Worker extracts from `inputData`:
   - `scriptName: String` — name of script in pages library (nullable if raw YAML provided)
   - `yaml: String` — raw YAML source (nullable if named script)
   - `params: Map<String, Object>` — parameters passed to the scenario
   - `paused: boolean` — whether to start paused (default `false`)
   - Exactly one of `scriptName` or `yaml` must be non-null. Both null → `PermanentFaultException`. Both provided → `scriptName` takes precedence (named script is the safer path).
4. Worker constructs GraphQL mutation:
   - Named script: `scenarioSubmit(scriptName, params, callbackUrl, dispatchId)`
   - Raw YAML: `scenarioSubmit(yaml, params, callbackUrl, dispatchId)`
5. Dispatches via Vert.x WebClient to the resolved endpoint URL
6. Registers `PendingCompletion` in `AsyncWorkerCompletionRegistry` with:
   - `dispatchId`, `workerType = "scenario"`, `faultAddress = SCENARIO_WORKER_FAULT`
   - `callbackToken`, `capability`, `eventLogId`
7. `callbackUrl` = `{casehub.workers.callback-base-url}/workers/complete/{dispatchId}`. The base URL is a shared config property (`casehub.workers.callback-base-url`) — the externally-reachable URL of the CaseHub engine instance. Required for any async worker that passes a callback URL to an external system.

### Completion Flow

1. Pages scenario engine finishes (all steps complete or failure with `on-error: stop`)
2. Pages POSTs to `callbackUrl` with:
   - `dispatchId` (opaque, passed at start)
   - `outcome`: `"completed"` or `"failed"`
   - `outputData`: map of step results or error details
3. `WorkerCallbackResource` receives it, completes via `AsyncWorkerCompletionRegistry`
4. On success: `WorkflowCompletionPublisher` fires `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED` with output data
5. On failure: `WorkerFaultCallbackObserver` routes to `SCENARIO_WORKER_FAULT` via `faultAddress` from `PendingCompletion`

Requires casehub-pages#416 (callback support in the scenario engine).

### Error Model

| Condition | Exception | Retryable? |
|-----------|-----------|------------|
| Pages unreachable / network error | `RuntimeException` | Yes |
| GraphQL error response (server error) | `RuntimeException` | Yes |
| Script not found (GraphQL error) | `PermanentFaultException` | No |
| GraphQL 4xx (except 429) | `PermanentFaultException` | No |
| GraphQL 429 with Retry-After | `RetryAfterException` | Yes (delayed) |
| Scenario step failure (via callback) | Fault via callback observer | Configurable via retry policy |
| Callback timeout (completion expired) | `CompletionExpiredEvent` → expiry observer | Via fault pipeline |

### Provisioner

`ScenarioReactiveWorkerProvisioner` — capability probe validates:
- Tag starts with `scenario:` prefix
- Endpoint resolver can resolve the tag for the given tenancyId
- Resolved URL is non-blank

### Runtime

`ScenarioWorkerRuntime` — validates:
- At least one endpoint is configured (config or EndpointRegistry)
- FAULTED if no endpoints configured; supports FAULTED → RUNNING recovery

## Key Types

| Type | Purpose |
|------|---------|
| `ScenarioWorkerConstants` | `WORKER_TYPE = "scenario"` |
| `ScenarioWorkerEventBusAddresses` | `SCENARIO_WORKER_FAULT = "casehub.workers.scenario.fault"` |
| `ResolvedScenarioEndpoint` | Record — `url`, `scriptName` |
| `ScenarioEndpointResolver` | `WorkerCapabilityResolver<ResolvedScenarioEndpoint>` — 3-tier with EndpointRegistry, tenant-aware |
| `ScenarioWorkerExecutionManager` | `@WorkerBackend @Priority(10)` — GraphQL dispatch via Vert.x WebClient, async with callback |
| `ScenarioReactiveWorkerProvisioner` | Capability probe — validates tag + endpoint resolution |
| `ScenarioWorkerFaultEventHandler` | `@ConsumeEvent(SCENARIO_WORKER_FAULT, blocking=true)` — 5-line stub delegating to `WorkerFaultHandler` |
| `ScenarioWorkerRuntime` | `WorkerRuntime` implementation — endpoint validation, lifecycle |

## Configuration

```properties
# Tier 2 — direct config
casehub.workers.scenario.endpoints.onboard.url=https://pages.example.com/graphql
casehub.workers.scenario.endpoints.seed-data.url=https://pages.example.com/graphql

# Callback base URL (shared across async workers)
casehub.workers.callback-base-url=https://engine.example.com

# Defaults
casehub.workers.scenario.default-timeout-seconds=600
```

## Cross-Repo Dependencies

| Dependency | Why | Status |
|------------|-----|--------|
| `casehub-pages` #416 | Callback support in scenario engine | Created, pending |
| `casehub-platform-api` | `EndpointProtocol.SCENARIO` enum value | Needed |
| `casehub-worker-api` | Worker Foundation records | Existing |
| `casehub-engine-api` | `ReactiveWorkerProvisioner` SPI | Existing |
| `casehub-engine-common` | `WorkerExecutionManager`, `WorkerBackend` | Existing |
| `casehub-platform-api` | `EndpointRegistry`, `EndpointProtocol` | Existing |
| engine#676 | `bindingName` parameter on `submit()` | Pending |

## Testing

- Unit tests for `ScenarioEndpointResolver` — all three tiers, tenant fallback, protocol filtering
- Unit tests for `ScenarioWorkerExecutionManager` — named script dispatch, raw YAML dispatch, error classification
- Unit tests for provisioner — tag validation, resolution success/failure
- Integration test with mock GraphQL server — full dispatch → callback → completion flow

## References

- `ScenarioControlResource.java` — pages REST API surface
- `ScenarioResolver.java` — pages GraphQL API surface
- `ScenarioOrchestrator.java` — pages execution engine
- `ScriptRegistry.java` — pages script library
- `HttpEndpointResolver.java` — 3-tier resolution reference implementation
- `McpServerResolver.java` — EndpointRegistry integration reference
- `WorkerCallbackResource.java` — callback endpoint in workers-common
- casehub-pages#416 — callback support issue
- Blog: 2026-08-29 "The scenario engine wants to be an automation platform"
- Blog: 2026-08-11 "Specifying a Scenario Format Before Building the Engine"
