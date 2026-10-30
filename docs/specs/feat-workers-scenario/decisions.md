# workers-scenario Design Decisions

## D1: Dispatch protocol

**Choice:** GraphQL (scenarioSubmit mutation)
**Alternatives:**
- REST (POST /scenario/start) — simpler but not type-safe
- MCP protocol — would require MCP server impl in pages
**Rationale:** Pages has a dual REST+GraphQL API. GraphQL is type-safe and consistent with how the platform exposes operations.
**Trade-offs:** Requires GraphQL client in the worker (Vert.x WebClient with manual query construction, same as other workers)
**Sources:** ScenarioControlResource.java, ScenarioResolver.java (GraphQL API in pages)
**Exploration:** quick
**Status:** captured

## D2: Completion model

**Choice:** Callback — pages POSTs to WorkerCallbackResource when scenario completes/fails
**Alternatives:**
- Poll scenarioStatus — simpler, no pages changes, but wastes resources and adds latency
- WebSocket subscription — real-time but adds WebSocket client complexity to the worker
**Rationale:** Callback aligns with the existing async worker pattern (AsyncWorkerCompletionRegistry + WorkerCallbackResource). No polling overhead. Clean separation.
**Trade-offs:** Requires changes to casehub-pages (casehub-pages#416 created). Pages must be able to reach the CaseHub engine's callback endpoint.
**Sources:** WorkerCallbackResource.java, AsyncWorkerCompletionRegistry.java, ScenarioOrchestrator.java
**Exploration:** quick
**Status:** captured

## D3: Endpoint resolution

**Choice:** 3-tier with EndpointRegistry, tenant-aware
**Alternatives:**
- Config-only single tier (like script/k8s) — simpler but not tenant-aware
**Rationale:** Scenario targets a pages service instance, not a stateless local function. Different tenants may have different pages deployments. All workers targeting service instances should be tenant-scoped.
**Trade-offs:** More complex resolver than config-only. Requires EndpointProtocol.SCENARIO in platform-api.
**Sources:** HttpEndpointResolver.java, McpServerResolver.java, EndpointRegistry (platform-api)
**Exploration:** quick
**Status:** captured

## D4: Capability tag format

**Choice:** `scenario:<script-name>` — tag directly names the script
**Alternatives:**
- `scenario:<instance>:<script>` (MCP-style) — more explicit but couples tags to deployment topology
**Rationale:** Follows the script:/k8s: single-level pattern. The endpoint (which pages instance) is resolved via EndpointRegistry, not encoded in the tag. Simpler capability registration.
**Trade-offs:** Multiple scripts on the same pages instance each get separate capability tags that resolve to the same endpoint URL.
**Sources:** ScriptWorkerConstants.java, McpWorkerConstants.java
**Exploration:** quick
**Status:** captured

## D5: Input mode

**Choice:** Both named scripts and raw YAML
**Alternatives:**
- Named scripts only — safer but less flexible
**Rationale:** Named scripts cover the normal case (pre-registered, parameterised). Raw YAML supports ad-hoc automation from case steps. The case definition author decides which to use.
**Trade-offs:** Raw YAML is less controlled — any valid scenario YAML can execute. Security boundary is the case definition, not the worker.
**Sources:** ScenarioLibraryResource.java, ScriptRegistry.java
**Exploration:** quick
**Status:** captured
