---
layout: post
title: "Watch-Based Completion — The Sixth Worker"
date: 2026-07-01
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Workers]
tags: [k8s-worker, informer, completion-model, fault-classification]
---

## Watch-Based Completion — The Sixth Worker

Five workers dispatch work and get a result. Script waits for process exit. HTTP and MCP wait for a response. HTTP async registers a callback and waits for a POST. Camel blocks on `ProducerTemplate`. Every completion model is either "wait for the answer" or "wait for someone to call you back."

The K8s worker (#10) breaks the pattern. You create a Job, and then you watch the cluster until the Job's status reaches a terminal state. Nobody calls you back. Nobody sends you a response. You observe a state change on an external system — that's the completion signal.

### The informer as completion mechanism

fabric8's `SharedIndexInformer` turned out to be the right abstraction. One informer per namespace, filtered by `app.kubernetes.io/managed-by=casehub`, fires `onUpdate` when any CaseHub-managed Job changes state. The Job carries a `casehub.io/dispatch-id` label that maps back to the `AsyncWorkerCompletionRegistry` entry — same registry HTTP async uses for callbacks.

The tricky part was `onAdd`. The natural instinct is "ignore it — we created the Job." But when the informer's watch connection drops (410 Gone from the API server), it does a full re-list. Jobs that completed during the disconnection window appear as `onAdd` events on reconnection — the only signal for those completions. Ignoring `onAdd` loses them. The design review caught this in round 1 and it took two more rounds to get the `onDelete` handling right (distinguishing TTL controller cleanup from genuine external deletion).

### Fault classification is where K8s gets interesting

HTTP has a clean model: 4xx is permanent, 5xx is retryable, 429 has Retry-After. K8s faults are contextual. `BackoffLimitExceeded` is permanent if `backoffLimit > 0` (K8s already retried) but retryable if `backoffLimit = 0` (single Pod failure, CaseHub decides). `DeadlineExceeded` is permanent but the real cause might be `ImagePullBackOff` — the Pod never started, it just sat in Pending until the deadline. The enrichment logic inspects the Pod's waiting state to surface the root cause.

### What the design review found

19 issues across 5 rounds. The substantive ones: input size guard (etcd's ~1.5MB limit makes unbounded env vars dangerous), `ttlAfterFinished` minimum 300s (prevents a race where the TTL controller deletes the Job before the informer processes it), tenant namespace isolation documentation, and RBAC requirements for operators. All verified and merged into the spec before implementation began.
