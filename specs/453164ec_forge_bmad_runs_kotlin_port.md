# Plan: Port Aggregate A8 "forge/BMAD + runs" to Kotlin/Spring in factory-service

## Summary
Port Aggregate A8 ("forge/BMAD + runs") from Node/TS (`factory/lib/forge-*.mjs`, `factory/dashboard/forge-routes.mjs`, `factory/dashboard/run-routes.mjs`, `factory/dashboard/workstream-routes.mjs`, `factory/dashboard/agentos-proxy.mjs`, `factory/lib/jira.mjs`) into Kotlin/Spring Boot inside `factory-service` (`io.whozoss.factory.forge.*` and `io.whozoss.factory.runs.*`).

---

## Architectural Boundaries & Strict Constraints

1. **Porting ONLY**: Do NOT wire the vanilla frontend cockpit to `factory-service`, and do NOT delete or modify the Node control plane (reserved for Wave W6b).
2. **REST / SSE Invariants**:
   - Serve the exact same REST/SSE contract for forge, runs, workstreams, AgentOS proxy, and Jira endpoints as Node.
   - All REST error responses MUST use the `{ "error": { "code": "...", "message": "...", "details": ... } }` envelope via `FactoryExceptionHandler`.
3. **Database & Schema Invariants**:
   - NO modifying or deleting existing database migrations V1->V9.
   - The `workstreams` table already exists in `V2__tenant_and_membership.sql`. Do NOT recreate or alter it!
   - Ledger/runs state MUST remain JSONL files (not stored in PostgreSQL tables), preserving JSONL ledger format, append-only semantics, parsing, and projections (`projectForgeRun`, `listForgeRunProjections`, `parseForgeLedger`, `createEpicRun`).
4. **SSE Specifics**:
   - Byte-by-byte SSE parity for `GET /api/runs/{id}/stream` and `GET /api/factory/runs/{id}/stream` using Spring `SseEmitter`.
   - Frame formatting with `tag: sse`.
   - Exclude SSE endpoints from generated OpenAPI spec using `@Operation(hidden = true)`.
   - Ensure error responses on SSE endpoints render as `application/json` (reusing `FactoryExceptionHandler`).
5. **W0 Architectural Patterns**:
   - Use `ScopedRepository` / `TenantScope` for database access.
   - Use `NamedParameterJdbcTemplate` for `workstreams` JDBC operations.
   - Require `TrustContext` identity (return `401 TRUST_CONTEXT_UNAVAILABLE` when missing/unauthenticated where required).
6. **Integration Tests**:
   - ALL new integration test classes MUST extend `DomainIntegrationTest`.
   - Do NOT introduce `@SpringBootTest` variants or new test container setups.
   - Validate using `./gradlew clean test`.
7. **OpenAPI Spec**:
   - Regenerate OpenAPI spec at the end via `./gradlew generateOpenApi`.

---

## Technical Components & Modules to Implement

### Package Structure: `io.whozoss.factory.forge` & `io.whozoss.factory.runs`

```
factory-service/src/main/kotlin/io/whozoss/factory/
├── forge/
│   ├── config/
│   │   └── ForgeProperties.kt             # Configuration properties (run store paths, jira configs, etc.)
│   ├── domain/
│   │   ├── ForgeLedger.kt                 # ForgeLedgerEvent models, FORGE_LEDGER_SCHEMA_VERSION, FORGE_WORKFLOW_VERSION
│   │   ├── ForgeSpec.kt                   # Spec models, YAML/frontmatter parsers, spec hashing (SHA-256)
│   │   ├── ForgeStorySpec.kt             # Story spec models, inheritance validation, story frontmatter parsing
│   │   ├── ForgeHumanDecision.kt         # G1 decision models, computeG1EvidenceSetHash
│   │   ├── ForgeG2.kt                    # G1/G2 evaluation rules and result models
│   │   ├── StoryAnalysis.kt              # Story analysis models, validation rules, prompt/brief builders
│   │   ├── StoryEdit.kt                  # Story edit models, diff validation
│   │   ├── StoryOracle.kt                # Story oracle models and catalog definition
│   │   ├── FrontOracleResolution.kt      # Front oracle plan resolution & Nx project inspection
│   │   └── WorkflowSync.kt               # adaptForgeRunToWorkflowProjection & sync logic
│   ├── port/
│   │   ├── ForgeLedgerStore.kt           # Interface for JSONL operations
│   │   ├── AgentOsProxyClient.kt         # Interface for AgentOS HTTP proxy operations
│   │   └── JiraClient.kt                 # Interface for Jira API integration
│   ├── infrastructure/
│   │   ├── FileForgeLedgerStore.kt       # JSONL file-backed implementation of ForgeLedgerStore
│   │   ├── HttpAgentOsProxyClient.kt     # RestTemplate/WebClient-backed AgentOS proxy
│   │   ├── HttpJiraClient.kt             # REST client for Jira API v3 (returns JIRA_NOT_CONFIGURED error when credentials missing)
│   │   └── JdbcWorkstreamRepository.kt   # NamedParameterJdbcTemplate repo for workstreams table
│   ├── service/
│   │   ├── ForgeRunService.kt            # High-level orchestration (createEpicRun, projectForgeRun, evaluate G1/G2)
│   │   ├── StoryOperationService.kt      # Story analysis, edit, and oracles execution & recording
│   │   └── WorkstreamService.kt          # Workstream querying & sync with forge/bmad/workstreams.toml or DB
│   └── web/
│       ├── ForgeRunController.kt         # REST endpoints for /api/forge/runs and /api/factory/forge/runs
│       ├── WorkstreamController.kt       # REST endpoints for /api/factory/workstreams
│       ├── AgentOsProxyController.kt     # REST endpoints for /api/agents and /api/cases/{caseId}/events
│       └── JiraProxyController.kt        # REST endpoints for /api/jira/{ticketId} and /api/factory/jira/{ticketId}
└── runs/
    ├── domain/
    │   └── LegacyRunModels.kt            # Run, Phase, RunStatus, RunDetail models
    ├── service/
    │   └── LegacyRunService.kt           # Run listing, detail, launching, stopping, and active run tracking
    └── web/
        ├── LegacyRunController.kt        # REST endpoints for /api/runs and /api/factory/runs
        └── LegacyRunSseController.kt     # SSE endpoints GET /api/runs/{id}/stream and /api/factory/runs/{id}/stream (@Operation(hidden=true))
```

---

## Detailed Implementation Plan

### Step 1: Forge Domain Models & Pure Functions (`io.whozoss.factory.forge.domain`)
- **`ForgeLedger.kt`**:
  - Define `ForgeLedgerEvent` data classes (sealed hierarchy or polymorphic Jackson mapping for `run_started`, `story_run_created`, `gate_started`, `human_decision_recorded`, `g2_evaluated`, `g2_us_evaluated`, `story_analysis_plan_validated`, `story_edit_finished`, `story_g3_evaluated`, `story_oracle_finished`, `agent_execution_finished`).
  - Implement `parseForgeLedgerLines(raw: String): List<ForgeLedgerEvent>` parsing JSONL.
  - Implement `projectForgeRun(events: List<ForgeLedgerEvent>): Map<String, Any?>?` deriving the complete display state.
  - Implement `listForgeRunProjections(runStoreRoot: Path): List<Map<String, Any?>>`.
- **`ForgeSpec.kt` & `ForgeStorySpec.kt`**:
  - Implement YAML frontmatter parsing and spec hashing (canonical SHA-256).
  - Implement spec schema validation (`FORGE_SPEC_SCHEMA_VERSION = 1`, `G2_POLICY_VERSION = "g2-policy-v1"`).
  - Implement story spec inheritance validation (`validateInheritance`).
- **`ForgeHumanDecision.kt` & `ForgeG2.kt`**:
  - Implement `computeG1EvidenceSetHash(events, runId, attempt, policyVersion)`.
  - Implement G1 and G2 evaluation logic (`evaluateG2`, `evaluateG2US`).
- **`StoryAnalysis.kt`, `StoryEdit.kt`, `StoryOracle.kt`**:
  - Implement story analysis brief construction, plan schema validation, story edit diff validation, oracle campaign execution & catalog evaluation.

### Step 2: Infrastructure Adapters (`io.whozoss.factory.forge.infrastructure`)
- **`FileForgeLedgerStore.kt`**:
  - File I/O operations for JSONL ledgers (`createEpicRun`, `appendToLedger`, `readLedgerLines`, `listProjections`).
  - Thread-safe append operations using file locks or atomic appends.
- **`HttpAgentOsProxyClient.kt`**:
  - Relay methods: `fetchAgents(namespaceId)`, `fetchNamespace(namespaceId)`, `fetchCaseEvents(caseId)`, `resolveRepoRoot(namespaceId)`, `resolveRunStoreRoot(namespaceId)`.
  - Propagate `X-External-User-Id` header from `TrustContext`.
  - Handle AgentOS 404 (return null) and offline errors (throw `AgentOsUnavailableException` mapped to `502 AGENTOS_UNAVAILABLE`).
- **`HttpJiraClient.kt`**:
  - Interface implementation for Jira Cloud API v3.
  - Check missing credentials: if `JIRA_BASE_URL`, `JIRA_EMAIL`, or `JIRA_API_TOKEN` are missing, throw `JiraNotConfiguredException` with status `501`, code `JIRA_NOT_CONFIGURED`, and exact error message:
    `"Le serveur du dashboard n'a pas de credentials Jira configurés (manquant : JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN). Relancez-le avec ces variables dans son environnement : JIRA_BASE_URL=https://votre-instance.atlassian.net JIRA_EMAIL=votre@email.com JIRA_API_TOKEN=votre-token node factory/dashboard/server.mjs"`
- **`JdbcWorkstreamRepository.kt`**:
  - Implement `ScopedRepository` using `NamedParameterJdbcTemplate` for queries on the existing `workstreams` table.

### Step 3: Legacy Runs & SSE Controllers (`io.whozoss.factory.runs`)
- **`LegacyRunController.kt`**:
  - `GET /api/runs` & `GET /api/factory/runs` (list runs, support `?namespaceId=`).
  - `GET /api/runs/{id}` & `GET /api/factory/runs/{id}` (run detail).
  - `POST /api/runs` & `POST /api/factory/runs` (launch run).
  - `POST /api/factory/runs/{id}/stop` (kill process with SIGTERM / mark stopping).
  - `GET /api/factory/runs/{id}/review-gate` & `POST /api/factory/runs/{id}/review-gate/reply`.
- **`LegacyRunSseController.kt`**:
  - `GET /api/runs/{id}/stream` & `GET /api/factory/runs/{id}/stream`.
  - Set `@Operation(hidden = true)` on both methods.
  - Return `SseEmitter` streaming log frames with `tag: sse`.
  - Error responses (e.g. run not found) MUST render as `application/json` envelope.

### Step 4: Forge REST Controllers & Proxies (`io.whozoss.factory.forge.web`)
- **`ForgeRunController.kt`**:
  - `GET /api/forge/runs` and `GET /api/factory/forge/runs`
  - `GET/POST /api/forge/runs/{id}/gates/G1` and `/gates/G2`
  - `POST /api/forge/runs/{id}/gates/G1/decision`
  - `GET/POST /api/forge/runs/{epicRunId}/stories/{storyRunId}/executions`
  - `GET/POST /api/forge/runs/{epicRunId}/stories/{storyRunId}/oracles`
  - `GET/POST /api/forge/runs/{epicRunId}/stories/{storyRunId}/edits`
  - `POST /api/factory/forge/runs/create`
- **`WorkstreamController.kt`**:
  - `GET /api/factory/workstreams` and `POST /api/factory/workstreams`
- **`AgentOsProxyController.kt`**:
  - `GET /api/agents` and `GET /api/cases/{caseId}/events`
- **`JiraProxyController.kt`**:
  - `GET /api/jira/{ticketId}` and `GET /api/factory/jira/{ticketId}`

### Step 5: Integration Tests (`io.whozoss.factory.forge.*` & `io.whozoss.factory.runs.*`)
All integration test classes MUST extend `DomainIntegrationTest`.
1. **`ForgeLedgerIntegrationTest.kt`**: Test parsing raw JSONL, append-only file operations, and `projectForgeRun` projection correctness.
2. **`ForgeGatesIntegrationTest.kt`**: Test G1 human decision recording, evidence hash computation, and G2 evaluation.
3. **`ForgeStoryOperationsTest.kt`**: Test story analysis execution, edit diff validation, and oracle campaign evaluation.
4. **`WorkstreamJdbcRepositoryTest.kt`**: Test JDBC CRUD operations on the `workstreams` table constrained by `TenantScope`.
5. **`AgentOsProxyMockTest.kt`**: Test AgentOS proxy endpoints with a WireMock server or MockRestServiceServer.
6. **`LegacyRunAndSseHttpTest.kt`**: Test legacy run REST endpoints, SSE stream byte framing (`tag: sse`), and JSON error framing on SSE route failures.
7. **`JiraProxyHttpTest.kt`**: Test 501 `JIRA_NOT_CONFIGURED` response when missing env vars vs successful ticket fetch.

### Step 6: Verification & OpenAPI Spec Regeneration
1. Run full test suite: `./gradlew clean test` (must pass cleanly).
2. Regenerate OpenAPI spec: `./gradlew generateOpenApi`.
3. Verify `openapi/openapi.json` and ensure SSE endpoints are excluded (`@Operation(hidden = true)`).

---

## Verification Checklist for Builder
- [ ] `./gradlew clean test` passes with 0 failures.
- [ ] No changes or deletions made to Flyway migrations V1->V9.
- [ ] `workstreams` table queried via `JdbcWorkstreamRepository` / `NamedParameterJdbcTemplate` with `TenantScope`.
- [ ] Forge JSONL ledgers created and projected on filesystem (not DB).
- [ ] SSE routes tagged `tag: sse`, marked `@Operation(hidden = true)`, and output JSON error envelope on failure.
- [ ] Missing Jira credentials returns 501 `JIRA_NOT_CONFIGURED` with exact message.
- [ ] `./gradlew generateOpenApi` executed and updated spec committed.
