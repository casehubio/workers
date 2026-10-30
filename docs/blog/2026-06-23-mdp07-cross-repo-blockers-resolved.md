---
layout: post
title: "Cross-Repo Blockers Resolved"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [tenancy, engine-integration, provisioner]
---

engine#530 and engine#531 both shipped while I was away. `ProvisionContext` now carries `tenancyId` as a first-class field, and the engine no longer hard-gates on `getCapabilities()` before calling `provision()`.

The workers-side integration was about as small as it gets. Four provisioners — Camel, HTTP, MCP, Script — each had a line reading `TenancyConstants.PLATFORM_TENANT_ID` with a comment pointing at the future: `// engine#530: context.tenancyId()`. I replaced each with `context.tenancyId()`, dropped the `TenancyConstants` import, and removed a dead helper method in `WorkerProvisionerSupport` that nobody was calling.

The tests caught the one thing worth noting. Three test files (Camel, HTTP, Script) passed `null` as the `ProvisionContext` — the old code never touched it, so nobody noticed. The MCP and GitHub Actions tests already used real contexts. Switching from a constant to `context.tenancyId()` turned that `null` into an NPE. The garden had already flagged this pattern (GE-20260601-a35fb3: tenancyId null causing silent failures in the in-memory repository). Same family of problem, different call site.

engine#531 required nothing on the workers side. The `getCapabilities()` gate was in the engine's `tryProvision()` — removing it means registry-only endpoints can now reach `provision()` via the fallback path. The workers' `getCapabilities()` implementations are unchanged and correct.

Two of the three cross-repo blockers listed in the handoff are now cleared. engine#461 (composite `WorkerExecutionManager`) remains — that's the one that actually requires design work.
