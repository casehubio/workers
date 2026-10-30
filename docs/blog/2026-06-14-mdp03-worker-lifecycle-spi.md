---
layout: post
title: "Worker Lifecycle — From Ad-Hoc CDI to a Real SPI"
date: 2026-06-14
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Workers]
tags: [lifecycle, mcp, spi-design, mutiny]
---

## Worker Lifecycle — From Ad-Hoc CDI to a Real SPI

Issue #7 started as "add `tools/list` discovery to the MCP worker." It turned into something bigger — a common lifecycle SPI for all four worker types, with MCP discovery as the first consumer.

The trigger was a question I asked myself during brainstorming: should dynamic discovery be MCP-specific, or should there be a common lifecycle? Every worker module had the same ad-hoc pattern — `@Observes StartupEvent` for init, `@PreDestroy` for cleanup (only MCP bothered), no shared vocabulary for status. Four implementations of the same lifecycle with no shared contract.

### Where the SPI lives

The instinct was to put `WorkerRuntime` in `casehub-engine-api` alongside `ReactiveWorkerProvisioner` and `WorkerExecutionManager`. The `Worker` model lives in the engine. The lifecycle should too, right?

Wrong. Every type in `engine-api`'s SPI package is consumed by the engine — the engine calls the provisioner, the engine calls the execution manager. `WorkerRuntime` has no engine consumer. Its only caller is `WorkerLifecycleOrchestrator` in `workers-common`. Putting it in engine-api would have created a type with no engine-side consumer, violating the dependency direction. The principle: SPI defined where consumed, not where its subject lives. Recorded as ADR-0001.

This also eliminated a cross-repo publish cycle. The entire implementation is a single PR against `casehub-workers`.

### The Mutiny partial-success gap

MCP servers initialize in parallel — three servers at 10 seconds each should take ~10s, not 30s. The natural Mutiny API is `Uni.join().all()`, but neither `andFailFast()` nor `andCollectFailures()` supports partial success. `andCollectFailures` sounds like it collects failures alongside successes — it doesn't. It throws a `CompositeException` and the successful results are gone.

The fix: wrap each server's init Uni so it always succeeds with a typed result object (`ServerInitResult.success(...)` or `ServerInitResult.failure(...)`). Then `Uni.join().all().andFailFast()` never fails, and we inspect the results to count successes and failures. If at least one server succeeded, the runtime is RUNNING. Submitted this as a garden entry — the method name genuinely misleads.

### The lazy-to-eager shift

The v1 MCP spec established lazy session initialization — sessions created on first dispatch, cached, reused. Discovery changes this: `tools/list` requires an initialized session, and capability discovery must complete before any dispatch so the provisioner knows what's available at case planning time.

The lazy infrastructure stays. `McpSessionManager.getOrInitialize()` is still the entry point. The runtime just pre-warms the cache at startup. The execution manager still calls `getOrInitialize()` on dispatch and gets a cache hit — or re-initializes after a 404 invalidation. Eager startup, lazy recovery.

### Status vocabulary

`WorkerRuntimeStatus` uses the same names as Serverless Workflow 1.0 where they map: PENDING, RUNNING, FAULTED. Added STOPPED as a terminal state for shutdown. The state machine supports FAULTED → RUNNING recovery — calling `initialize()` on a faulted runtime retries without application restart.

The status reflects initialization outcome only. Post-init dispatch failures go through the per-dispatch fault pipeline. A future `healthCheck()` method could surface runtime-level degradation, but there's no consumer for that today.

The SPI placement decision feels like a small instance of a broader pattern emerging across CaseHub: the dependency direction principle matters more than conceptual grouping. A type belongs where it's consumed, even when its name suggests it belongs somewhere else.
