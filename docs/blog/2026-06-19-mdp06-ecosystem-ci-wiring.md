---
layout: post
title: "Wiring the ecosystem — CI, origins, and the redirect that bit us"
date: 2026-06-19
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-workers]
tags: [ci, infrastructure, github-packages]
---

This was an infrastructure session. CaseHub Workers had no CI, 14 repos were pushing to personal forks, and the parent build scripts didn't know half the ecosystem existed.

## The audit

I started by checking what was actually wired up. The answer was sobering: workers had zero CI runs ever. Engine, parent, and casehub-all all needed workers added to their trigger chains, build scripts, and submodule pointers. But the bigger finding was that 14 of 22 repos still had their git origin pointing at `mdproctor/` rather than `casehubio/`. The CI trigger chain references `casehubio/$repo/dispatches` — so the published artifacts lived on casehubio, but every developer push went to a personal fork.

A `git remote set-url` pass across all 15 repos (including parent, which I initially missed) fixed the origin problem.

## Build infrastructure gaps

Six repos — iot, neural-text, desiredstate, ras, ops, and quarkmind — were completely absent from the build infrastructure. They had CI workflows on GitHub but weren't in `build-all.sh`, `aggregator.xml`, or `update-pointers.yml`. Three more (openclaw, life, drafthouse) were in `build-all.sh` but missing from the aggregator's `-Papps` profile.

The local `flow` directory turned out to be stale — the GitHub repo had been renamed to `scaffold`. Removed it from the build scripts and updated engine's downstream trigger to use the canonical name.

Claudony and aml both had partial CI — build and test only, no deploy step. Added full publish workflows and the `<distributionManagement>` config both were missing.

## Three CI failures and what they taught me

**The redirect that isn't.** Workers' pom.xml used `casehubio/casehub-workers` in its `distributionManagement` URL. GitHub's web UI, API, and git clone all follow the redirect to `casehubio/workers` transparently. The Maven registry does not. Deploy failed with "Could not find artifact" — a misleading error that looks like a permissions problem. The fix is to use the canonical repo name from `gh repo view --json name`.

**The broken pipe race.** Script worker tests passed locally on macOS but were flaky on Ubuntu CI. Fast commands like `echo` exit before the Java caller finishes writing to stdin. The `IOException` triggered the fault path instead of completion. The fix: check `process.isAlive()` before treating a broken pipe as fatal.

**The moving SNAPSHOT.** Between two pushes, engine CI went green and published a new SNAPSHOT with a different constructor signature. My code matched the old version, which was correct when I pushed but wrong by the time CI pulled the dependency. The underlying problem — no way to pin a SNAPSHOT in CI short of installing from a specific git commit locally — is inherent to SNAPSHOT development across repos.

The parent README was significantly out of date. Rewrote the build status tables, ecosystem project listings, and CI/CD section to reflect the full trigger chain. The "Adding a new project" checklist now includes casehub-all and trigger wiring steps.
