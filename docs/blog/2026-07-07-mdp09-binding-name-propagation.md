---
layout: post
title: "Threading bindingName Through the Worker Pipeline"
date: 2026-07-07
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Workers]
tags: [workers, fault-pipeline, k8s, spi]
series: issue-18-k8s-binding-name-propagation
---

*Part of a series on [#18 — propagate bindingName through worker completion path](https://github.com/casehubio/workers/issues/18). Previous: [K8s Restart Recovery](2026-07-03-mdp08-k8s-restart-recovery.md).*

## The Problem Nobody Hit Yet

When a worker completes, `WorkflowCompletionPublisher` fires a `WorkflowExecutionCompleted` event. That event carries `bindingName` — which binding in the case definition triggered this worker. The engine uses it to route the completion back to the right place.

Except we were always passing `null`.

The engine has a fallback: `findMatchingCapabilityBinding()` iterates all bindings looking for one whose capability matches. Works fine when there's one binding per capability. Falls apart the moment a case definition binds the same capability to different workers — the engine picks whichever it finds first.

The `bindingName` was right there in the `WORKER_SCHEDULED` EventLog metadata. The engine's `WorkerScheduleEventHandler` writes it at dispatch time. But `WorkerExecutionManager.submit()` had no parameter to carry it, so workers never received it and the completion path always sent `null`.

## What Changed

`WorkerCorrelationContext` — the per-dispatch bag of state that flows through every worker — gained a `bindingName` field. Since `PendingCompletion` holds the correlation context, and every completion, callback, and recovery path flows through `PendingCompletion.correlationContext()`, the field propagates automatically. One record change, and the REST callback resource, the Camel producer, the K8s informer, the expiry observer — all get `bindingName` for free.

The fault pipeline needed explicit wiring. `WorkerFaultEvent` gained `bindingName` so the `WorkerFaultHandler` can thread it through retry re-dispatch and retries-exhausted publishing. Before this, the fault handler was passing `worker.name()` as a surrogate for `bindingName` in `publishRetriesExhausted()` — wrong, but harmless as long as there's only one binding per capability.

All six workers — HTTP, MCP, Camel, Script, GitHub Actions, K8s — now override a 6-arg `submit()` that accepts `bindingName`. The old 5-arg delegates to it with `null`. Until the engine starts calling the new overload (engine#676), everything behaves identically to today.

## K8s: Annotation, Not Label

K8s needs `bindingName` to survive process restart. The obvious choice is a Job label — that's where all the other recovery metadata lives (`casehub.io/case-id`, `casehub.io/worker-name`, etc.). But K8s labels have a 63-character limit, and `bindingName` is user-defined.

An annotation has no length limit and is the right K8s primitive for data that's read during recovery but never used in label-selector queries. `K8sJobInformerManager.recoverFromJob()` now reads from `job.getMetadata().getAnnotations()` — null-safe for pre-upgrade Jobs that don't have it.

## What's Blocked

The workers-side code is complete. `bindingName` flows through every path: normal completion, fault, retry, exhaustion, REST callback, Camel producer, K8s recovery, `schedulePersistedEvent`. But it's all `null` until engine#676 ships — that's the SPI change that makes `WorkerScheduleEventHandler` call the 6-arg `submit()` with the actual value.

The engine change is small: a default method overload on `WorkerExecutionManager`, a `CompositeWorkerExecutionManager` routing override, and one call-site update. Once it lands, `bindingName` flows end-to-end without any further workers-side work.
