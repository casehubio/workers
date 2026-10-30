---
layout: post
title: "K8s Jobs Already Survive Restarts — We Just Weren't Listening"
date: 2026-07-03
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Workers]
tags: [k8s, recovery, restart, informer, design-review]
---

The sixth worker module — workers-k8s — shipped two days ago. A K8s Job can run for hours. The in-memory completion registry doesn't survive a process restart. That's a qualitatively different problem from HTTP workers where timeouts are measured in seconds.

The issue had been filed during the k8s design review with a suggested approach: re-list Jobs on startup, re-register pending completions for running Jobs. I started there and quickly found the approach was too shallow. It assumed the only problem was reconnecting to existing Jobs. There's a second failure mode: what if the process crashed *before* the K8s Job was even created? The engine has a `WORKER_SCHEDULED` event log entry with no corresponding Job in the cluster. That work is permanently lost.

## The Root Insight

The real insight came from asking what the K8s Job actually carries. Labels: dispatch-id, capability, tenancy-id, managed-by. Env vars: case-id, idempotency, input data. To deliver a completion, you need a `CaseInstance` and a `Worker`. The CaseInstance can be loaded from the database. The Worker — which the completion handler only uses for its `name()` and `capabilityNames()` — can be reconstructed from two strings.

The problem wasn't that Jobs lacked recovery data. It was that the data was incomplete (no case-id in labels, no worker name anywhere) and the completion path hard-dropped events when the registry was empty.

So the fix is three things: enrich the Job labels so they're self-describing, make `processTerminal()` reconstruct the context when the registry is empty, and implement `schedulePersistedEvent()` for the "no Job exists" case.

## The Startup Race Nobody Knew About

The design review surfaced something unexpected. The engine's `WorkerRecoveryCoordinator` fires at `@Priority(22)`. The worker orchestrator fires at `@Priority(APPLICATION + 10)` — that's 2010. Recovery runs *first*. It calls `schedulePersistedEvent()`, which routes through the composite to the K8s backend, which calls `supports()`, which asks the resolver for capabilities. But the resolver hasn't been initialized yet — it loads from Config inside `initialize()`, which the orchestrator hasn't called because the orchestrator fires at priority 2010.

The fix is embarrassingly simple: `@PostConstruct` on the resolver. Config is injected by CDI — it's available at bean creation time. One annotation, zero I/O, and the race disappears.

## The At-Most-Once Guard

The trickiest piece is preventing duplicate completions during recovery. The informer fires `onAdd` for existing Jobs on startup. If a Job completes and gets cleaned up, the informer also fires `onDelete`. Both call `processTerminal()`. In normal operation, `registry.complete()` is atomic — `ConcurrentHashMap.remove()` returns the entry exactly once. After a restart, the registry is empty. Both callbacks hit the recovery path. Both reconstruct a `PendingCompletion`. Both publish a completion.

The fix: a `recoveredDispatchIds` set backed by `ConcurrentHashMap.newKeySet()`. The guard uses `add(dispatchId)` — a single atomic operation that returns `true` if newly added, `false` if already present. Same semantics as `registry.complete()`, different data structure.

The design review caught this one. Four rounds, seventeen issues. The reviewer also caught the startup ordering race and the fact that `idempotency` was buried in env vars instead of labels — reading `job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv()` and iterating is fragile when a label would do.

The K8s cluster was already the durable store. The Jobs already survived restarts. We just needed to stop ignoring what was already there.
