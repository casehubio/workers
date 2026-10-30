---
layout: post
title: "CaseHub Workers — MCP: Any Protocol Server as a Worker"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [mcp, workers, protocol, session-management]
---

# CaseHub Workers — MCP: Any Protocol Server as a Worker

**Date:** 2026-06-12
**Type:** phase-update

---

## What I was trying to achieve: one worker module for hundreds of integrations

CaseHub has three worker modules — HTTP endpoints, Apache Camel routes, GitHub Actions workflows. Each handles one dispatch protocol. MCP (Model Context Protocol) servers are everywhere now — Slack, Jira, databases, cloud APIs — and each one exposes a standardised tool interface. Instead of building a CaseHub worker per service, I wanted a single module that could call any MCP server's tools.

## What I believed going in: it's just JSON-RPC over HTTP

My initial read was that this would be the simplest worker yet. Lighter than HTTP (no 3-tier endpoint resolution), lighter than Camel (no route lifecycle). Just POST a `tools/call`, get a response, fire completion. The spec review process corrected that assumption hard.

## The protocol has teeth

MCP isn't a bare JSON-RPC API. It's a stateful session protocol with a mandatory initialisation handshake — three HTTP roundtrips before you can call any tool. The server may assign a session ID that must be echoed on every subsequent request. Sessions expire, signalled by HTTP 404, requiring transparent re-initialisation. The response can arrive as plain JSON or as a Server-Sent Events stream. The client must accept both. Progress notifications. Cancellation signals on timeout.

Five review cycles surfaced 24 issues. The first draft treated MCP servers as HTTP endpoints that spoke JSON-RPC. By the final revision, the spec covered session lifecycle, concurrent initialisation deduplication, dual content-type response handling, protocol version negotiation, and a detailed fault classification table that distinguishes session expiry from endpoint-not-found — both return 404, but only one is retryable.

## The concurrent initialisation problem

Two case steps dispatching to the same MCP server simultaneously. Both see "no session." Both trigger initialisation. The MCP server gets two handshake requests and behaves unpredictably.

The fix uses a Mutiny pattern I'd seen before in Qhorus: `ConcurrentHashMap.computeIfAbsent()` with `Uni.memoize().indefinitely()`. The map operation is atomic — only one `Uni` is created per server name. The memoisation ensures the initialisation HTTP calls only execute once. All concurrent callers await the same result. The critical detail is failure eviction: `onFailure().invoke(() -> sessions.remove(k))` must be chained *before* `memoize()`, not after. If initialisation fails, the cached `Uni` is removed so the next dispatch retries fresh. Chain it after `memoize()` and the failure is permanently cached.

## `isError` doesn't mean permanent

The spec's own example of a tool returning `isError: true` is "API rate limit exceeded" — a textbook transient failure. My first draft classified all `isError` results as `PermanentFaultException`, which would have stalled cases on rate limits instead of retrying. The reviews caught this. The right default is retryable — if retries exhaust on a genuinely permanent error, the cost is a few wasted attempts. Skip retries on a transient error and the case stalls forever.

The same asymmetry applied to malformed responses. A load balancer returning an HTML error page during a container restart looks like a protocol violation, but it's transient. Making malformed responses permanent would stall cases on infrastructure blips that clear in seconds.

## What it is now

Ten classes, 56 tests, the lightest worker module in the repo if you subtract the session manager. The session manager is the only genuinely new concept — the other workers don't need protocol handshakes. Everything else follows the established pattern exactly: `ReactiveWorkerProvisioner`, `WorkerExecutionManager`, fault publisher, fault event handler, config-based resolver.

The MCP proxy idea — CaseHub exposing itself as an MCP server that aggregates backend tools — is parked in IDEAS.md. It's the inbound complement to this outbound worker, and it would share the session management infrastructure. Dynamic tool discovery via `tools/list` at startup is filed as #7.
