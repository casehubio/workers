---
layout: post
title: "Tenancy-Aware Endpoint Resolution"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [endpoint-registry, tenancy, spi-design]
---

The workers repo had a structural placeholder for EndpointRegistry since the HTTP module was first built — a comment in `HttpEndpointResolver.initialize()` that said "when EndpointRegistry ships, add a tier here." Platform#73 shipped weeks ago. Time to wire it in.

The interesting question wasn't *how* to connect two systems — it was what happens when you try to plug a tenancy-scoped, runtime-dynamic registry into a startup-static, tenant-blind resolver interface. Four mismatches surfaced immediately: keying (capability tag vs Path), tenancy (no `tenancyId` parameter), timing (static HashMap vs runtime registration), and provisioner visibility (`firstMatch()` can't probe the registry without a tenant context).

I considered three approaches. Injecting EndpointRegistry alongside the resolver (Approach B) would duplicate fallback logic across every execution manager and provisioner. Merging registry endpoints into the HashMap at startup (Approach C) can't handle tenant-specific endpoints — which is the primary use case. The right answer was Approach A: change `WorkerCapabilityResolver` itself. Add `tenancyId` to `resolve()` and `firstMatch()`. Break every caller. The breakage is the point — it forces all four resolvers, four execution managers, and four provisioners to be explicit about tenancy.

The resolution order is clean: Tier 1 (SPI beans) > Tier 2 (config) > Tier 3 (EndpointRegistry). A single registry call with the actual `tenancyId` — the registry handles tenant-to-platform-global fallback internally. `InMemoryEndpointRegistry.resolve()` already does this: tries tenant-specific first, falls back to `PLATFORM_TENANT_ID`. The workers don't need to know.

MCP had a subtlety I hadn't anticipated. HTTP maps 1:1 — one capability tag, one endpoint. MCP maps N:1 — `mcp:slack:send-message` and `mcp:slack:list-channels` both resolve to the same server at `Path.of("mcp", "slack")`. The registry entry is per-server, not per-tool. That means `firstMatch()` can only validate server existence at probe time — tool validation is deferred to dispatch, when `McpSessionManager.getOrInitialize()` lazily creates the session and runs `tools/list`. This is the same lazy validation pattern already used for 404 session recovery, so it fits naturally.

Two engine-side gaps surfaced during design review. `ProvisionContext` doesn't carry `tenancyId` even though the engine has it from `CaseInstance` and already passes it to `terminate()`. Filed engine#530. More interesting: the engine's `tryProvision()` hard-gates on `getCapabilities()` before calling `provision()`. Registry-only endpoints never reach `provision()` because the static capability set doesn't include them. Filed engine#531 — the fix is three lines. Neither blocks the primary dispatch path (`WorkerExecutionManager.submit()`), which is fully tenant-aware from day one.

An upstream SNAPSHOT break complicated the session. Engine-common deleted `WorkflowExecutionFailed` and changed `WorkflowExecutionCompleted.approved()` from four arguments to five — no deprecation, no migration note. The non-obvious part: workers used `WorkflowExecutionFailed` as the event payload type on *worker-specific* fault addresses, not on the deleted `WORKFLOW_EXECUTION_FAILED` address. The address cleanup was correct; the type deletion was broader than it looked. Fix was straightforward — a local `WorkerFaultEvent` record in workers-common — but the failure mode is worth remembering.

The design went through two review cycles with seven findings before implementation. The most useful was finding that `getCapabilities()` is a hard gate in the engine — something I wouldn't have caught from the workers side alone.
