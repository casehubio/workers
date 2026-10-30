---
layout: post
title: "The Architecture Record That Reads Itself"
date: 2026-06-17
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Workers]
tags: [arc42stories, documentation, architecture]
series: issue-11-arc42stories
---

*Part of a series on [#11 — docs: create ARC42STORIES.MD](https://github.com/casehubio/casehub-workers/issues/11). Previous: [The Fifth Worker and the Extraction It Forced](2026-06-16-mdp04-fifth-worker-extraction.md).*

Five worker types shipped in eight days. Six design specs, three blog entries, one ADR, twenty-five commits. The delivery history existed — scattered across git log, design specs, and CLAUDE.md's growing key-types tables. What didn't exist was a single document that showed how the pieces fit together architecturally.

ARC42STORIES.MD is the architecture record for casehub-workers. Arc42Stories extends arc42 with delivery planning (Chapters) and implementation detail (Layers) — the two dimensions that arc42 leaves implicit. I'd just finished the connectors version the day before, so the structural pattern was fresh.

## The mapping exercise

The interesting part wasn't writing — it was the mapping that preceded it. Six chapters fell out naturally from the git log: each `feat(*)` commit is a chapter boundary. But the layers needed thought. The obvious grouping is one layer per worker type, which gives you five layers that look identical in structure. The non-obvious grouping is the shared infrastructure that cuts across all of them.

Two layers — shared worker infrastructure (L1) and fault pipeline (L2) — appear in every column of the layer x chapter matrix. They bear cross-cutting responsibility. The per-worker layers (L4-L8) each have a single High entry at introduction, then Low entries from the lifecycle retrofit (C5) and fault extraction (C6). The Low entries after introduction are the stability signal: once a worker lands, it barely changes.

The worker runtime lifecycle (L3) is the oddity — it arrived in Chapter 5, after four workers had already shipped with ad-hoc startup patterns. It retrofitted all four, then C6 retrofitted all five again for the fault pipeline. Both retrofits were motivated by the same thing: the Nth consumer revealing that the pattern should have been generic from the start.

## What the quality sweep found

The three-check sweep — verify file paths, issue references, blog cross-references — caught two stale references. platform#73 (EndpointRegistry) and parent#225 (PLATFORM.md sync) had both closed since the last session. The document would have launched with "not yet shipped" and "open" claims about work that was already done.

This is the failure mode the garden entries warned about: ARC42STORIES.MD references decay across sessions and across repos. An issue closes in a different repo, in a different session, and nothing updates the reference here. The stale scan at session wrap exists for exactly this reason.

## The pattern that emerged

Writing the layer entries exposed a structural regularity I hadn't articulated. Every worker module follows the same template: constants class, event bus addresses, capability resolver, execution manager, provisioner, runtime, fault handler stub. The fault handler stub is always five lines delegating to the shared `WorkerFaultHandler`. The provisioner always validates the capability tag exists in the resolver. The runtime always delegates `initialize()` to the resolver.

The "Pattern to replicate" sections in section 9.4 capture this as numbered steps — domain-agnostic instructions for building the next worker. If the K8s worker (#10) happens, the template is there.
