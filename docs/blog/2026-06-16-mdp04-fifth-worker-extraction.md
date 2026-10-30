---
layout: post
title: "The Fifth Worker and the Extraction It Forced"
date: 2026-06-16
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Workers]
tags: [script-worker, fault-pipeline, extraction, subprocess]
---

## The Fifth Worker and the Extraction It Forced

The script worker (#9) is the simplest worker in the collection — run a subprocess, pipe JSON to stdin, capture stdout, check the exit code. No protocol negotiation, no session management, no async callbacks. `ProcessBuilder` and a timeout.

What made it interesting was the prerequisite. Four worker modules had accumulated four copies of the same fault pipeline — publisher, handler body, CDI event observers. Each differed only by a constant (the event bus address). Adding a fifth copy was the wrong answer, so the script worker spec grew a §2: extract the fault pipeline to `workers-common` before building the new module.

### What the extraction found

The fault publisher was the easy case — four identical classes that differed by one string constant. A single `WorkerFaultPublisher` parameterized by address replaced all four.

The fault handler body was the same story: persist failure, check for `PermanentFaultException`, count attempts, retry or exhaust. ~90 lines duplicated across HTTP, MCP, GitHub Actions, and Camel. The extraction exposed a bug: the Camel handler was missing the `PermanentFaultException` check entirely — it retried permanent faults up to `maxAttempts` times. This bug existed because Camel was written before `PermanentFaultException` was extracted to `workers-common`; the three later workers each added the check independently. A shared handler fixes it for everyone.

The CDI event observers needed a different approach. The per-module observers filtered events by `workerType` and routed them to the per-module publisher. The generic replacement needed routing without per-module filtering. The solution: add `faultAddress` to `PendingCompletion`. Each pending completion becomes self-routing — the generic observer publishes to whatever address the pending completion carries. No registry, no `workerType` filter, no new SPI method.

### The `runSubscriptionOn` lesson

The spec review caught a subtle Mutiny correctness issue. I'd written "no `emitOn` needed in the fault handler" — wrong. When a fault handler uses a Vert.x timer for backoff delay and re-dispatches via `flatMap(submit)`, `submit()`'s `runSubscriptionOn` only affects the inner subscription. The `flatMap` itself runs on the timer's event-loop thread. An `emitOn(workerPool)` before the `flatMap` is required.

The Camel fault handler already had this `emitOn` for the right reason. The shared handler now always uses it — one unnecessary thread hop for reactive workers on the error path is negligible, and correctness regardless of what any `submit()` does internally is worth more than one thread hop.

### The script worker itself

Eight classes. Config-driven named scripts (`casehub.workers.script.scripts.<name>.*`) map capability tags (`script:data-pipeline`) to executables with arguments, working directory, environment, and timeout. Execution is synchronous via `ProcessBuilder` on the Mutiny worker pool (`runSubscriptionOn`). Input data arrives as JSON on stdin with dispatch context in environment variables (`CASEHUB_CASE_ID`, `CASEHUB_TENANCY_ID`, `CASEHUB_CAPABILITY`, `CASEHUB_IDEMPOTENCY`).

Output handling: if stdout is a JSON object, it becomes the structured output map. Arrays, primitives, and invalid JSON fall through to a raw wrapper (`{stdout, stderr, exitCode}`). Stream draining uses bounded reads — 8KB chunks, cap at `maxOutputBytes`, continue draining past the cap to prevent SIGPIPE.

Timeout is `PermanentFaultException` — a divergence from HTTP and MCP where timeouts are retryable. A subprocess that burned 300 seconds will burn 300 seconds again. Each retry wastes an OS process and a worker-pool thread for the full duration. Non-zero exit codes are retryable via the standard fault pipeline.

### The numbers

Phase 1 (extraction): -1,391 lines net. 8 classes deleted, 4 fault handlers shrunk from ~90 lines to 5-line stubs. Phase 2 (script worker): 8 new classes, 25 tests. Total: 61 files changed, +2,161/-1,539 across 17 commits.

The extraction pattern — "extract shared infrastructure as a prerequisite before adding the Nth consumer" — is now established twice in this repo: first with `PermanentFaultException`/`RetryAfterException` (GitHub Actions spec, June 10), now with the full fault pipeline. The next worker type starts with the infrastructure already in place.
