# CaseHub Workers

## Project Type

type: java

## Repository Role

Integration-tier collection of CaseHub worker implementations. Each module provides `ReactiveWorkerProvisioner` and `WorkerExecutionManager` SPI implementations (from `casehub-engine-api` and `casehub-engine-common`) that allow CaseHub cases to dispatch work to different execution runtimes — HTTP endpoints, Apache Camel routes, shell scripts, Kubernetes Jobs, and more.

**Tier:** Integration (alongside `claudony` and `casehub-openclaw` in the build order)

**Design philosophy:** Thin wrappers — each worker module translates a CaseHub case step dispatch into the target runtime's protocol and fires `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED` when done. No domain logic here.

**Spec:** `docs/superpowers/specs/2026-06-08-casehub-workers-camel-design.md` — fully approved, 7 review cycles.

## Build Commands

```bash
# Build all modules
mvn --batch-mode install

# Publish to GitHub Packages (CI only — requires GITHUB_TOKEN)
mvn --batch-mode deploy -DskipTests
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `workers-common` | `casehub-workers-common` | `io.casehub.workers.common` | General async worker infrastructure — shared by all worker types |
| `workers-http` | `casehub-workers-http` | `io.casehub.workers.http` | HTTP/webhook worker — 3-tier endpoint resolution, sync/async dispatch |
| `workers-camel` | `casehub-workers-camel` | `io.casehub.workers.camel` | Apache Camel worker — 300+ connectors |
| `workers-github-actions` | `casehub-workers-github-actions` | `io.casehub.workers.githubactions` | GitHub Actions worker — workflow_dispatch + repository_dispatch |
| `workers-mcp` | `casehub-workers-mcp` | `io.casehub.workers.mcp` | MCP worker — dispatch case steps to MCP server tools via Streamable HTTP |
| `workers-script` | `casehub-workers-script` | `io.casehub.workers.script` | Script worker — dispatch case steps to local subprocesses (shell, Python, JS) |
| `workers-testing` | `casehub-workers-testing` | `io.casehub.workers.testing` | Shared test fixtures — **test scope only, never compile/runtime** |

Sub-packages follow function: `.registry`, `.callback`, `.fault`, `.route`, `.component` as needed within each root package.

**Build order:** `workers-common` must be first in parent POM `<modules>` — all others depend on it.

## Engine Integration — Two SPIs, Two Call Sites

Workers implement two engine SPIs — these are called at different times:

| SPI | Call site | Purpose for Camel |
|-----|-----------|-------------------|
| `ReactiveWorkerProvisioner` | `CaseContextChangedEventHandler.tryProvision()` | Capability probe — validates route exists, returns `ProvisionResult.empty()` |
| `WorkerExecutionManager` | `WorkerScheduleEventHandler` | Actual dispatch — sends exchange, manages completion |

Both are `@ApplicationScoped` (no `@DefaultBean`). CDI displaces `NoOpReactiveWorkerProvisioner` and `NoOpWorkerExecutionManager` when Camel beans are present.

## workers-common Key Types

| Type | Purpose |
|------|---------|
| `PendingCompletion` | Registry entry per async dispatch — carries `dispatchId`, `workerType`, `faultAddress`, `callbackToken`, `capability`, `eventLogId`. Self-routing: `faultAddress` enables generic observers without per-module filtering |
| `WorkerCorrelationContext` | Per-dispatch context — `CaseInstance`, `Worker`, `idempotency`, `tenancyId` |
| `AsyncWorkerCompletionRegistry` | In-memory pending completion store; `expireStale()` fires `CompletionExpiredEvent` CDI async |
| `WorkflowCompletionPublisher` | Fires `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED` via `eventBus.publish()` |
| `WorkerCallbackResource` | `POST /workers/complete/{dispatchId}` — REST callback for external systems |
| `WorkerRetrySupport` | Shared retry building blocks — `persistFailureLog`, `countFailedAttempts`, `publishRetriesExhausted`, `resolveRetryPolicy`, `computeBackoffDelayMs`, `parseRetryAfter` |
| `PermanentFaultException` | Worker-agnostic "don't retry" signal — extracted from workers-http |
| `RetryAfterException` | Worker-agnostic "retry after delay" signal — extracted from workers-http |
| `FaultCallbackEvent` | CDI async event fired by `WorkerCallbackResource` on faulted REST callback |
| `CompletionExpiredEvent` | CDI async event fired by `AsyncWorkerCompletionRegistry.expireStale()` |
| `CasehubWorkerHeaders` | Header name constants shared across all worker types |
| `WorkerRuntime` | Lifecycle SPI — `initialize()`, `shutdown()`, `capabilities()`, `status()`. All worker types implement this. Orchestrator discovers beans via CDI |
| `WorkerRuntimeStatus` | `PENDING` → `RUNNING` → `STOPPED`, `PENDING` → `FAULTED` → `STOPPED`, `FAULTED` → `RUNNING` (recovery). Aligned with SW 1.0 vocabulary |
| `WorkerLifecycleOrchestrator` | `@ApplicationScoped` — discovers all `WorkerRuntime` beans, calls `initialize()` at startup (`@Priority(APPLICATION + 10)`), `shutdown()` at `@PreDestroy`. Sequential across types, fail-open per worker |
| `WorkerFaultPublisher` | Generic fault publisher — parameterized by fault address. Two overloads: `fault(faultAddress, ctx, capability, eventLogId, cause)` and `fault(pending, cause)`. Replaces all per-module fault publishers |
| `WorkerFaultHandler` | Shared fault handler body — persist → PermanentFaultException check → count → RetryAfterException check → retry-or-exhaust. Always uses `emitOn(workerPool)` before re-dispatch. Per-module fault event handlers are 5-line stubs delegating here |
| `WorkerCompletionExpiryObserver` | Generic `@ObservesAsync CompletionExpiredEvent` — routes via `faultAddress` from `PendingCompletion`. Replaces per-module expiry observers |
| `WorkerFaultCallbackObserver` | Generic `@ObservesAsync FaultCallbackEvent` — routes via `faultAddress` from `PendingCompletion`. Replaces per-module callback observers |

## workers-camel Key Types

| Type | Purpose |
|------|---------|
| `CamelWorkerConstants.WORKER_TYPE = "camel"` | workerType discriminator — passed to `register()`, used by CDI observers to filter events |
| `CamelWorkerEventBusAddresses.CAMEL_WORKER_FAULT` | Separate fault address from Quartz's `WORKFLOW_EXECUTION_FAILED` |
| `CamelWorkerFaultEventHandler` | `@ConsumeEvent(CAMEL_WORKER_FAULT, blocking=true)` — 5-line stub delegating to `WorkerFaultHandler` |
| `CamelWorkerRuntime` | `WorkerRuntime` implementation — delegates to `CamelCapabilityResolver.initialize()` |

## workers-http Key Types

| Type | Purpose |
|------|---------|
| `HttpWorkerConstants.WORKER_TYPE = "http"` | workerType discriminator |
| `HttpWorkerEventBusAddresses.HTTP_WORKER_FAULT` | Separate fault address from Camel and Quartz |
| `HttpWorkerRoute` | SPI interface for Tier 1 endpoint registration |
| `HttpEndpointResolver` | 3-tier capability tag → `ResolvedEndpoint` resolution (SPI bean > config > EndpointRegistry) |
| `HttpWorkerExecutionManager` | Sync/async dispatch via Vert.x WebClient — reactive-native, no `emitOn` needed |
| `HttpWorkerFaultEventHandler` | `@ConsumeEvent(HTTP_WORKER_FAULT, blocking=true)` — 5-line stub delegating to `WorkerFaultHandler` |
| `ExchangeMode` | `SYNC` (default) or `ASYNC` |
| `HttpWorkerRuntime` | `WorkerRuntime` implementation — delegates to `HttpEndpointResolver.initialize()` |

## workers-github-actions Key Types

| Type | Purpose |
|------|---------|
| `GitHubActionsWorkerConstants.WORKER_TYPE = "github-actions"` | workerType discriminator |
| `GitHubActionsWorkerEventBusAddresses.GITHUB_ACTIONS_WORKER_FAULT` | Separate fault address from HTTP and Camel |
| `GitHubActionsTokenResolver` | Per-org + global PAT resolution from config properties |
| `GitHubActionsWorkerExecutionManager` | Dispatches via Vert.x WebClient — fire-and-forget on 204 |
| `GitHubActionsWorkerFaultEventHandler` | `@ConsumeEvent(GITHUB_ACTIONS_WORKER_FAULT, blocking=true)` — 5-line stub delegating to `WorkerFaultHandler` |
| `GitHubActionsReactiveWorkerProvisioner` | Capability probe — validates tags and token availability |
| `GitHubActionsWorkerRuntime` | `WorkerRuntime` implementation — validates token config; FAULTED if no token, supports FAULTED → RUNNING recovery |

## workers-mcp Key Types

| Type | Purpose |
|------|---------|
| `McpWorkerConstants.WORKER_TYPE = "mcp"` | workerType discriminator |
| `McpWorkerEventBusAddresses.MCP_WORKER_FAULT` | Separate fault address from HTTP, Camel, and GitHub Actions |
| `McpServerResolver` | Config + discovery server registry — N:1 capability tag mapping (`mcp:<server>:<tool>` → `ResolvedMcpServer`). `discovery=auto` (default) calls `tools/list`; `discovery=manual` is config-only. `registerDiscoveredTools()` merges discovered tools with config allowlist |
| `McpSessionManager` | `@ApplicationScoped` — MCP session lifecycle: eager init at startup (pre-warmed by `McpWorkerRuntime`), concurrent dedup via memoized Uni, session caching, shutdown via `McpWorkerRuntime.shutdown()` |
| `McpSession` | Per-server runtime state — `sessionId`, `protocolVersion`, `AtomicLong requestIdCounter` |
| `McpWorkerExecutionManager` | Dispatches `tools/call` via Vert.x WebClient — dual response parsing (JSON + SSE), `structuredContent` preferred |
| `McpWorkerFaultEventHandler` | `@ConsumeEvent(MCP_WORKER_FAULT, blocking=true)` — 5-line stub delegating to `WorkerFaultHandler` |
| `McpReactiveWorkerProvisioner` | Capability probe — validates tag in resolved set, server URL non-blank |
| `McpWorkerRuntime` | `WorkerRuntime` implementation — parallel server init via `Uni.join().all()` with per-server error isolation (`ServerInitResult`), `tools/list` discovery, eager session pre-warming, delegated shutdown |
| `ServerInitResult` | Per-server init outcome record — success (session + discovered tools) or failure (error). Enables partial-failure handling |

## workers-script Key Types

| Type | Purpose |
|------|---------|
| `ScriptWorkerConstants.WORKER_TYPE = "script"` | workerType discriminator |
| `ScriptWorkerEventBusAddresses.SCRIPT_WORKER_FAULT` | Separate fault address from HTTP, Camel, GitHub Actions, and MCP |
| `ScriptDefinition` | Record — `name`, `command`, `args`, `workingDirectory`, `environment`, `timeoutSeconds`, `maxOutputBytes` |
| `ScriptDefinitionResolver` | `WorkerCapabilityResolver<ScriptDefinition>` — config-driven (`casehub.workers.script.scripts.<name>.*`), capability tag prefix `script:` |
| `ScriptWorkerExecutionManager` | Dispatches via `ProcessBuilder` — `runSubscriptionOn(workerPool)`, stdin JSON delivery, bounded stdout/stderr capture, exit code classification. Owns dedicated `ExecutorService` for stream draining (`@PostConstruct`/`@PreDestroy` lifecycle) |
| `ScriptWorkerFaultEventHandler` | `@ConsumeEvent(SCRIPT_WORKER_FAULT, blocking=true)` — 5-line stub delegating to `WorkerFaultHandler` |
| `ScriptReactiveWorkerProvisioner` | Capability probe — validates tag exists in resolver, command non-blank |
| `ScriptWorkerRuntime` | `WorkerRuntime` implementation — delegates to `ScriptDefinitionResolver.initialize()`. Zero scripts → FAULTED |

## Key Rules

- `workers-testing` is never a compile or runtime dependency — test scope only.
- Each worker module activates by classpath presence (`@ApplicationScoped`, no config required to enable).
- Workers are stateless — all state in the case instance or external system, never in provisioner beans.
- `tenancyId` propagated through all calls — bind in Repository layer only (PP-20260520-e6a5f0).
- Completion fires `eventBus.publish()` on `WORKER_EXECUTION_FINISHED` — never `request()`. Two consumers exist (`WorkflowExecutionCompletedHandler` + `PlanItemCompletionHandler`); `publish()` delivers to both.
- Worker faults fire on worker-specific addresses (`CAMEL_WORKER_FAULT`, `HTTP_WORKER_FAULT`, `GITHUB_ACTIONS_WORKER_FAULT`, `MCP_WORKER_FAULT`, `SCRIPT_WORKER_FAULT`), NOT `WORKFLOW_EXECUTION_FAILED` — Quartz listens on the latter and would double-process.
- Fault pipeline is centralized in workers-common: `WorkerFaultPublisher` (parameterized by address), `WorkerFaultHandler` (shared retry body), `WorkerCompletionExpiryObserver` and `WorkerFaultCallbackObserver` (generic, route via `faultAddress` from `PendingCompletion`). Per-module fault handlers are 5-line stubs.
- `WorkerFaultHandler` always uses `emitOn(Infrastructure.getDefaultWorkerPool())` before re-dispatch — correct for all workers regardless of whether their `submit()` is blocking or reactive. One unnecessary thread hop for reactive workers is negligible on the error path.
- Retry logic via `WorkerRetrySupport`: `failureCount < retryPolicy.maxAttempts()` (strict `<`); null policy defaults to `new RetryPolicy()` (3 attempts, 10s FIXED).
- HTTP 4xx (except 429) throws `PermanentFaultException` — skips retry immediately.
- HTTP 429 with `Retry-After` header throws `RetryAfterException` — overrides configured backoff delay.
- GitHub Actions 422 on `workflow-dispatch` throws `RetryAfterException(60_000)` — workflow_dispatch trigger caching (GE-20260426-805acb). 422 on `repository-dispatch` throws `PermanentFaultException` — malformed request.
- GitHub Actions `ref` is required for `workflow-dispatch` — GitHub API rejects requests without it.
- MCP dispatch is event-loop native via WebClient — no thread hop needed in execution manager.
- MCP `isError: true` is retryable (not permanent) — MCP spec's own example is "API rate limit exceeded."
- MCP malformed responses are retryable — load balancer HTML pages, proxy timeouts are transient.
- MCP 404 with active `Mcp-Session-Id` → session expired, retryable (re-initializes). 404 without session → `PermanentFaultException` (endpoint not found).
- MCP session initialization uses `ConcurrentHashMap.computeIfAbsent` + `Uni.memoize().indefinitely()` — `onFailure().invoke(remove)` BEFORE `memoize()` (GE-20260609-78dc3a).
- MCP protocol version: `2025-06-18` only. No backwards compatibility with `2024-11-05` HTTP+SSE transport.
- MCP required headers: `Accept: application/json, text/event-stream`, `MCP-Protocol-Version`, `Mcp-Session-Id` (when assigned).
- MCP discovery mode: `discovery=auto` (default) calls `tools/list` at startup; `discovery=manual` is config-only (v1 behaviour).
- MCP `tools` config property is an allowlist when `discovery=auto` — config tools are always registered; discovered tools not in config are ignored. Config tools not found in `tools/list` trigger a warning but are kept (trust the operator).
- MCP session initialization is eager at startup (shift from v1 lazy model) — `McpWorkerRuntime.initialize()` pre-warms the session cache. Lazy infrastructure (`getOrInitialize()`) stays for dispatch-time re-init after 404.
- MCP per-server initialization is parallel within the runtime (`Uni.join().all()` with `ServerInitResult` error isolation). Partial failure: RUNNING if at least one server succeeds.
- Script execution uses `runSubscriptionOn(Infrastructure.getDefaultWorkerPool())` — same as Camel's blocking `ProducerTemplate`. Entire ProcessBuilder lifecycle runs on the worker pool.
- Script stdin delivers `inputData` as JSON. Env vars provide dispatch context: `CASEHUB_CASE_ID`, `CASEHUB_TENANCY_ID`, `CASEHUB_CAPABILITY`, `CASEHUB_IDEMPOTENCY`.
- Script stdout parsing: JSON object → structured output map; JSON array, primitive, or invalid JSON → raw wrapper `{stdout, stderr, exitCode}`.
- Script timeout → `PermanentFaultException` (diverges from HTTP/MCP where timeout is retryable). Rationale: subprocess that burned full timeout will timeout again; each retry wastes OS process + thread for full duration.
- Script non-zero exit → `RuntimeException` (retryable). Command not found or working directory missing → `PermanentFaultException`.
- Script stream draining: bounded read loop (8KB chunks, cap at `maxOutputBytes`, drain past cap to prevent SIGPIPE). Dedicated `ExecutorService` for stream draining — `@PostConstruct`/`@PreDestroy` lifecycle on execution manager.
- Worker lifecycle: all workers implement `WorkerRuntime`. `WorkerLifecycleOrchestrator` calls `initialize()` at startup, `shutdown()` at `@PreDestroy`. Initialization order across worker types is undefined.
- Worker runtime status reflects initialization outcome only — post-init dispatch failures go through the per-dispatch fault pipeline, not runtime status.
- FAULTED → RUNNING recovery: calling `initialize()` on a FAULTED runtime retries initialization.

## Co-deployment Constraints

- `workers-camel` + `scheduler-quartz` on same classpath → CDI ambiguity on `WorkerExecutionManager` → startup failure. Unsupported until a composite manager is built in engine.
- `workers-camel` + `workers-http` → `workerType` discriminator in `PendingCompletion` prevents double CDI event handling. `WorkerExecutionManager` CDI ambiguity still applies — same composite manager needed.
- `workers-github-actions` + any other worker → same `WorkerExecutionManager` CDI ambiguity. `workerType` discriminator prevents event cross-talk.
- `workers-mcp` + any other worker → same `WorkerExecutionManager` CDI ambiguity. `workerType = "mcp"` discriminator prevents event cross-talk.
- `workers-script` + any other worker → same `WorkerExecutionManager` CDI ambiguity. `workerType = "script"` discriminator prevents event cross-talk.

## Cross-Repo Dependencies

| Dependency | Why |
|---|---|
| `casehub-engine-api` | `ReactiveWorkerProvisioner`, `WorkerExecutionManager`, `Worker`, `Capability`, `ExecutionPolicy`, `RetryPolicy`, `BackoffStrategy` |
| `casehub-engine-common` | `WorkflowExecutionCompleted`, `WorkflowExecutionFailed`, `CaseInstance`, `EventLog`, `EventBusAddresses`, `WorkerExecutionKeys`, `EventLogRepository` |
| platform#73 | `casehub-endpoints` — `EndpointRegistry` SPI for named endpoint resolution (workers designed to work without it until it ships) |
| engine#461 | Composite `WorkerExecutionManager` — required for co-deploying HTTP + Camel + Quartz on same classpath |

## Cross-Repo Conventions

Protocols live in `casehub/garden`. Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| writing-plans (plans) | workspace `plans/` |
| handover | workspace `HANDOFF.md` |
| idea-log | workspace `IDEAS.md` |
| design-snapshot | workspace `snapshots/` |
| adr | `docs/adr/` |
| write-blog | workspace `blog/` |

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` |
| blog       | workspace   | staged here; published via publish-blog |
| specs      | project     | lands in `docs/superpowers/specs/` |
| plans      | workspace   | |
| design     | project     | journal in workspace `design/`; merge target is project `ARC42STORIES.MD` |
| handover   | workspace   | |

Living docs — check for drift after significant changes:
- `ARC42STORIES.MD` — primary architecture record; check §9–10 after worker module, SPI, or fault pipeline changes

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/casehub-workers

## Workspace

**Project repo:** `/Users/mdproctor/claude/casehub/workers`
**Workspace:** `/Users/mdproctor/claude/public/casehub-workers`
**Workspace type:** public

Git discipline — always use explicit paths:
```bash
git -C /Users/mdproctor/claude/public/casehub-workers ...   # workspace artifacts
git -C /Users/mdproctor/claude/casehub/workers ...          # project artifacts
```
