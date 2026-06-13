# 0001 — Worker Runtime SPI Placement

Date: 2026-06-14
Status: Accepted

## Context and Problem Statement

The `WorkerRuntime` lifecycle SPI needs a home module. The natural candidate is `casehub-engine-api` (alongside `ReactiveWorkerProvisioner` and `WorkerExecutionManager`), since `Worker` as a concept lives in the engine. But the engine does not consume `WorkerRuntime` — only `WorkerLifecycleOrchestrator` in `workers-common` does.

## Decision Drivers

* SPI should be defined where it is consumed, not where its subject lives
* Avoid types in engine-api with no engine consumer — false dependency signal
* Eliminate cross-repo coordination (engine-api publish + snapshot cycle) when unnecessary
* All four worker modules already depend on workers-common

## Considered Options

* **Option A** — Place in `casehub-engine-api` (`io.casehub.api.spi`)
* **Option B** — Place in `workers-common` (`io.casehub.workers.common`)

## Decision Outcome

Chosen option: **Option B** — `workers-common`, because the engine does not consume `WorkerRuntime`. Its only consumer is `WorkerLifecycleOrchestrator`, also in `workers-common`. The dependency graph is clean and no cross-repo coordination is needed.

### Positive Consequences

* No cross-repo publish cycle — entire implementation is a single PR
* Dependency direction is correct — SPI defined where consumed
* No false signal that the engine manages worker lifecycle

### Negative Consequences / Tradeoffs

* If a future engine component (health endpoint, dashboard) needs `WorkerRuntimeStatus`, it must be extracted to engine-api at that point — a mechanical migration

## Pros and Cons of the Options

### Option A — casehub-engine-api

* ✅ Conceptual alignment — `Worker` lives in engine, lifecycle could too
* ✅ Future consumers in engine wouldn't need extraction
* ❌ Engine doesn't consume it — creates a type with no engine caller
* ❌ Requires cross-repo coordination (engine-api publish before workers can use it)
* ❌ Violates dependency direction principle

### Option B — workers-common

* ✅ Consumed where defined — `WorkerLifecycleOrchestrator` is in the same module
* ✅ No cross-repo coordination
* ✅ All worker modules already depend on workers-common
* ❌ Future engine consumers would require extraction (mechanical)

## Links

* [Spec: Worker Runtime Lifecycle + MCP Dynamic Tool Discovery](../superpowers/specs/2026-06-13-worker-runtime-lifecycle-mcp-discovery-design.md)
* casehubio/casehub-workers#7
