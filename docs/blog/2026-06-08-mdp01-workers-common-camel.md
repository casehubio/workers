---
layout: post
title: "Workers-Common and Workers-Camel — First Real Worker Modules"
date: 2026-06-08
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [camel, workers, spi, integration-tier]
---

## The spec came out of a parent session

The design session in `casehubio/parent` that produced the Desired State Management research
also surfaced the need for a proper workers collection. Claudony and OpenClaw are
integration platforms — they own their repos. But thin SPI implementations like HTTP
endpoints, Camel routes, and shell scripts belong together. Seven review rounds later,
the Camel worker spec was approved and I had a clear picture of what to build.

## Two modules, one key decision

The implementation splits into `workers-common` (shared async infrastructure) and
`workers-camel` (the Camel adapter). The split exists because every future worker type —
HTTP, Script, MCP, K8s Job — needs the same completion registry, callback endpoint, and
event bus publishing. Building it into the Camel module would mean extracting it later.

The interesting design decision was fault bus isolation. The engine's
`QuartzWorkerExecutionJobListener` handles `WORKFLOW_EXECUTION_FAILED` with no worker-type
filter — it processes everything on that address. If Camel fires on the same address, two
things break: Quartz schedules a Quartz job for a Camel worker (wrong execution type), and
the failure count increments twice per fault (one write per handler), throwing the retry
comparison off by one.

The fix: Camel faults fire on `CAMEL_WORKER_FAULT`, a completely separate event bus address.
`CamelWorkerFaultEventHandler` handles it with retry logic that mirrors the Quartz
implementation exactly — same `failureCount < maxAttempts` comparison (strict less-than),
same default policy (3 attempts, 10s fixed), same backoff computation. Future worker types
will get their own fault addresses. The `workerType` discriminator on `PendingCompletion`
extends this pattern to CDI async events — `CompletionExpiredEvent` and `FaultCallbackEvent`
are broadcast to all observers, but each observer filters by worker type before acting.

## Threading was the subtlest part

The retry path after a Vert.x timer had a threading trap. The timer fires on the event
loop. `runSubscriptionOn` moves the subscription lambda to the worker pool, but item
delivery still happens on whoever calls `emitter.complete()` — the timer callback, which
is on the event loop. `emitOn` intercepts between emitter and downstream and re-dispatches
to the pool. The same issue appeared in `submitAsync` — `producerTemplate.send()` is a
blocking Camel call, and wrapping it in `.invoke()` without `runSubscriptionOn` would block
the event loop thread.

## What shipped

`workers-common` provides `AsyncWorkerCompletionRegistry` (ConcurrentHashMap-based, with
`@Scheduled` expiry), `WorkflowCompletionPublisher` (fires on `WORKER_EXECUTION_FINISHED`
via `eventBus.publish()`, not `request()` — two consumers need it), `WorkerCallbackResource`
(`POST /workers/complete/{dispatchId}` with constant-time token validation), and the record
types that carry dispatch context through the async completion lifecycle.

`workers-camel` implements both engine SPIs — `ReactiveWorkerProvisioner` for capability
probe, `WorkerExecutionManager` for dispatch. `CamelCapabilityResolver` does three-tier
resolution: CDI-registered `CamelWorkerRoute` beans first, then config properties, then
convention (route ID = capability tag with `from: direct:{tag}`). The `casehub:complete`
Camel component lets routes signal completion from within the Camel route itself.

The co-deployment constraint is documented but not solved: `workers-camel` and
`scheduler-quartz` on the same classpath causes CDI ambiguity on `WorkerExecutionManager`.
A composite routing manager in the engine is the right answer. That's a separate issue.

47 tests across both modules. The POM structure follows the casehub family pattern —
`casehub-parent` as parent, Quarkus Platform Camel BOM imported alongside the core BOM,
Jandex indexes on both library modules.
