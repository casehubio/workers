---
layout: post
title: "GitHub Actions as a CaseHub Worker"
date: 2026-06-11
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [workers, github-actions, architecture]
---

The third worker module shipped: `workers-github-actions`. Two GitHub APIs — `workflow_dispatch` and `repository_dispatch` — exposed as CaseHub capability tags. A case step can now trigger any GitHub Actions workflow as part of its lifecycle.

## The design question that mattered

The consolidation question came first: should this be a separate module, or should it build on `workers-http`? GitHub Actions dispatch is fundamentally an HTTP POST to `api.github.com`. The obvious move was to make it an `HttpWorkerRoute` plugged into the existing HTTP infrastructure.

I decided against it for three reasons. The fault model is different — a 422 on `workflow_dispatch` means "trigger definition not cached yet, try again in a minute" (a genuine GitHub-side caching gotcha), while a 422 on `repository_dispatch` means "your request is malformed, stop trying." Forcing both through `PermanentFaultException` would silently exhaust retries on a transient condition. The auth model is different — cross-repo `repository_dispatch` needs a classic PAT, not any token. And the endpoint resolution is unnecessary — GitHub's URL is deterministic from `owner + repo + workflow_id`. No resolver needed when there's nothing to resolve.

## Extracting the fault vocabulary

Building a new worker module exposed a dependency problem. `PermanentFaultException` and `RetryAfterException` lived in `workers-http`, but they're not HTTP concepts. "Don't retry" and "retry after this delay" apply to any worker talking to an external API. The GitHub Actions module couldn't use them without depending on the HTTP module, which would have been architecturally wrong.

We extracted both to `workers-common`, along with the `Retry-After` header parser. The HTTP worker became a consumer of its own former types. The Camel worker doesn't use `PermanentFaultException` yet, but it will — Camel routes that signal permanent failure currently just retry until exhaustion.

## 422 differentiation

The most interesting design detail is the 422 handling. Same status code, opposite meanings:

- **workflow_dispatch 422** → `RetryAfterException(60_000)`. The workflow file might have just had the trigger added, but GitHub's compiled workflow cache hasn't refreshed. A 60-second delay gives it time. The default retry policy (3 attempts, 10s FIXED) would exhaust in 30 seconds — before the cache refreshes.

- **repository_dispatch 422** → `PermanentFaultException`. Bad event type or malformed payload. The inputData is replayed from the EventLog on retry, so the same request will fail identically.

This is the kind of thing that justifies a separate module over a generic HTTP route — the domain semantics of the status code depend on which API you called.

## Fire-and-forget

The completion model is deliberately simple. GitHub returns 204 on successful dispatch — no response body, no run ID, no handle to track. We publish `WorkflowExecutionCompleted` immediately. The case step is done.

There's no `AsyncWorkerCompletionRegistry`, no pending completions, no expiry observers. We considered wiring them up as infrastructure for a future polling model, but registering a pending completion and immediately removing it on 204 creates a timing window — the expiry timer could fire before the response arrives on a slow API call. Better to add the infrastructure when the completion model actually evolves.

The `getActiveWorkCount()` method returns 0. There's nothing to count.
