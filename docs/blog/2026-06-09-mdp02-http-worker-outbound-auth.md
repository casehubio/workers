---
layout: post
title: "The HTTP Worker and the Outbound Auth Gap"
date: 2026-06-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [workers-http, outbound-auth, platform-coherence, retry]
---

I came into this session expecting the HTTP worker to be straightforward — simpler than the Camel worker, reusing `workers-common` for the heavy lifting, done in a day. The Camel worker had already established the pattern: two SPIs, fault pipeline, CDI observers with `workerType` filters, `AsyncWorkerCompletionRegistry` for async dispatch. The HTTP worker follows the same architecture with a different transport.

What I didn't expect was that the value proposition question — "why would a Quarkus developer who already deploys Camel routes as web services use a CaseHub worker?" — would surface a missing platform-level concern.

## The value proposition problem

A developer comfortable with Camel on Quarkus already has `@Retry`, `onException`, `IdempotentConsumer`, and MicroProfile Telemetry. Telling them "workers give you retry" is not a convincing argument. The real differentiator is orchestration and lifecycle: long-lived state across steps, process visibility ("show me all claims stuck at approval"), and ad-hoc intervention (skip, retry from a point, escalate). The per-route plumbing is table stakes. The case lifecycle is the value.

This led to a cleaner framing: standalone services that work fine without case context shouldn't be workers. Existing services that a case needs to call get an HTTP worker (zero friction — the service doesn't change). New integrations built for a case flow get a Camel worker. The HTTP worker is the on-ramp.

## The outbound auth gap

When I asked how the HTTP worker should handle authentication with external services, the answer forced a broader question: what does *any* CaseHub component use when calling external services? Workers, connectors (`casehub-connectors`), and quarkus-flow `call: http` steps all make outbound HTTP calls. None of them had a shared auth vocabulary.

Serverless Workflow 1.0 already defines one — `AuthenticationPolicy` with Basic, Bearer, Digest, OAuth2, and OIDC, plus named policy references. quarkus-flow implements it. The HTTP worker should use it. And so should everything else.

I added an **Outbound Authentication** section to PLATFORM.md establishing this as the canonical model. Two tiers: static deploy-time credentials via `@ConfigProperty` (what connectors already do — correct for single-vendor integrations), and named endpoint credentials via `EndpointRegistry` (platform#73, when it ships). The HTTP worker uses Tier 1 for now and is designed to activate Tier 2 by classpath presence.

## WorkerRetrySupport extraction

The Camel fault handler has five retry building blocks — persist failure, count attempts, resolve policy, compute backoff, publish exhaustion. The HTTP fault handler would have copied them verbatim. With a third worker type planned (script), that's three copies of identical logic.

We extracted `WorkerRetrySupport` to `workers-common` and refactored the Camel handler to use it. The HTTP handler was built on it from the start. What stays in each handler: fault classification (HTTP adds `PermanentFaultException` for 4xx and `RetryAfterException` for 429), threading (Camel needs `emitOn(workerPool)` because `ProducerTemplate` blocks; HTTP doesn't because `WebClient` is event-loop native), and the fault event bus address.

## The HTTP-specific design choices

Three things the HTTP worker does that the Camel worker doesn't:

**`PermanentFaultException` for 4xx.** A 400 Bad Request won't succeed on retry — the `inputData` is replayed from `EventLog`, so the same malformed request fires again. The fault handler persists the failure for observability but skips retry immediately. 429 is the exception: it's transient, and the `Retry-After` header tells you when to try again.

**URI template interpolation.** URLs can contain `{fieldName}` placeholders interpolated from `inputData` at dispatch time. Without this, any API with path parameters (`/orders/{orderId}/ship`) requires a Camel route — which undermines the "zero-friction" pitch. Missing keys throw `PermanentFaultException` because the same `inputData` replays on retry.

**No `emitOn` on retry.** Vert.x `WebClient` is event-loop native. The Camel worker's `emitOn(Infrastructure.getDefaultWorkerPool())` exists because `ProducerTemplate.request()` blocks. The HTTP worker's retry path fires the timer on the event loop, then calls `submit()` on the event loop, then `WebClient` sends non-blocking. Adding `emitOn` would waste a context switch and misrepresent the architecture.

## The spec review caught real things

The spec went through three review cycles and 24 issues. The dependency bug (the spec listed `quarkus-vertx` for WebClient, but WebClient lives in `smallrye-mutiny-vertx-web-client`) would have been a compile failure. The `Retry-After` TTL capping gap — async 429 responses with long `Retry-After` values could exceed the `PendingCompletion` TTL, causing duplicate fault processing — was a real race condition. The URI template exception type was wrong: `WorkerProvisioningException` implies "the worker can't be set up," but a missing inputData key is a data contract violation. `PermanentFaultException` is correct because the data doesn't change on retry.

The platform now has a second worker type alongside Camel, a shared retry infrastructure in `workers-common`, and an outbound auth coherence policy that the next worker (script, MCP, GitHub Actions) inherits automatically.
