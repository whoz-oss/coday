# Plan W8.4 — Projection Timeline Multi-Lanes Cockpit + Import de Définition

> Statut : Plan de mise en œuvre pour la tâche W8.4 (dernière étape de la vague W8).
> Emplacement du spec dans le dépôt : `specs/c5ddc17d_w84_multi_lanes_projection_and_definition_import.md`
> Base : `integration/factory-kotlin-w8.3`

---

## 1. Context and Goals

### Problem Statement
In W8.3, `SessionRunService` in `factory-service` implemented automatic DAG sequencing, persistence of `workflow_step_states`, `workflow_transitions`, `workflow_evidence`, capability resolution (`agent`, `code`, `human`), and baseline projection persistence. However:
1. Multi-lane projection fields (`lane`, actor name/kind, explicit execution timestamps `startedAt`/`completedAt` per step) are incomplete or implicitly formatted in projection outputs, needed for rendering multi-lane swimlane timelines in the Cockpit.
2. Declarative session definitions (schema `forge-story-fullstack-ux` style) need an explicit, valid HTTP import/upsert route (`PUT/POST /api/factory/workflow-definitions`), auto-validated by `WorkflowDefinitionValidator` / `SessionDefinitionValidator`, and bundled with a concrete example session definition in resources for seeding/bootstrapping.
3. The vanilla Node Cockpit (`factory/dashboard/js/...`) needs verification and minimal adaptation to render multi-lane swimlanes (`agent`, `code`, `human`) faithfully from the projection attributes served by `factory-service` (and streamed via SSE), preserving the existing frontend contract without migrating Node origin to Kotlin (W6b).

### Constraints & Non-Goals
- **Do not touch frozen Flyway migrations (V1..V9)**. Re-use existing `workflow_instances.projection_json` / `workflow_projections`.
- **Do not break schema v1 / v2 projection contracts**. Adding optional `lane`, `startedAt`, `completedAt`, `durationMs` to step projection objects in `WorkflowProjection.kt` must remain fully backward-compatible.
- **Do not migrate Cockpit host to Kotlin in W8.4**. Cockpit origin switch and complete Node toolchain removal is W6b. Cockpit in W8.4 stays vanilla JS consuming REST/SSE.
- **Do not break Node verification instrument or forge plugin**.
- **Tests**: All Spring / Kotlin integration tests must extend `DomainIntegrationTest` (reusing Testcontainers PostgreSQL context, no standalone `@SpringBootTest`). Node tests (`factory/tests/test-projection-governance.mjs`) must pass cleanly.

---

## 2. Detailed Technical Design & Scope

### Component 1: Kotlin `factory-service` Multi-Lane Projection Support

#### 1.1 `WorkflowProjection.kt` updates
- Extend `STEP_FIELDS` in `WorkflowProjection.kt` to allow optional fields: `"lane"`, `"startedAt"`, `"completedAt"`, `"durationMs"`.
- When normalizing step projection objects, if `responsibility` is present, derive `lane = step.responsibility.kind.wire` (`"agent" | "code" | "human"`).
- Pass through `startedAt`, `completedAt`, `durationMs` if present in input maps.

#### 1.2 `SessionRunService.kt` projection persistence enrichment
- Update `SessionRunService.persistProjection`:
  - Query step states (`workflow_step_states`) or execution timing for timestamps (`startedAt`, `completedAt`) and duration.
  - Enrich step maps in `projection["steps"]` with:
    - `"lane"`: `step.responsibility.kind.wire` (`"agent"`, `"code"`, `"human"`).
    - `"responsibility"`: `mapOf("kind" to step.responsibility.kind.wire, "name" to step.responsibility.name)`.
    - `"startedAt"`: ISO-8601 timestamp string from step state / evidence / transition if started.
    - `"completedAt"`: ISO-8601 timestamp string if completed/failed.
    - `"durationMs"`: calculated duration in milliseconds if completed/failed.
- Maintain SSE stream dispatch via `repository.publishProjection(...)` so SSE clients receive `workflow-projection-updated` events with multi-lane projection attributes in real time.

---

### Component 2: Declarative Session Definition Import & Seed Resource

#### 2.1 Endpoint & Validation
- Verify `WorkflowDefinitionController.kt`:
  - `POST /api/factory/workflow-definitions` (and ensure `PUT /api/factory/workflow-definitions/{type}/{version}` or `POST` upsert semantics exist/work as expected).
  - Validates payload through `WorkflowDefinitionValidator.validate(body)` which incorporates `SessionDefinitionValidator.validateGraph(steps)` (DAG uniqueness, valid `dependsOn`, acyclic graph, valid responsibility kinds `agent|code|human`).
  - Stores definition record in `workflow_definitions` table with computed canonical hash `hashWorkflowDefinition(definition)`.

#### 2.2 Sample Seed Definition Resource
- Create `factory-service/src/main/resources/sessions/forge-story-fullstack-ux.json` (or under `factory-service/src/main/resources/workflows/forge-story-fullstack-ux/1.0.0.json`).
- Structure matching the `forge-story-fullstack-ux` declarative definition:
  - `schemaVersion`: `"1"`
  - `workflowType`: `"forge-story-fullstack-ux"`
  - `version`: `"1.0.0"`
  - `title`: `"Forge Fullstack Story with UX & Verification"`
  - Steps sequence with `agent`, `human`, and `code` responsibilities:
    1. `ticket-analysis` (`agent`: `ForgeProductWorker`, `dependsOn`: `[]`)
    2. `intent-checkpoint` (`human`: `Product owner`, `dependsOn`: `["ticket-analysis"]`)
    3. `product-specification` (`agent`: `ForgeProductWorker`, `dependsOn`: `["intent-checkpoint"]`)
    4. `product-checkpoint` (`human`: `Product owner`, `dependsOn`: `["product-specification"]`)
    5. `ux-design` (`agent`: `ForgeUXDesigner`, `dependsOn`: `["product-checkpoint"]`)
    6. `codebase-research` (`agent`: `Searcher`, `dependsOn`: `["product-checkpoint"]`)
    7. `ux-checkpoint` (`human`: `Product owner`, `dependsOn`: `["ux-design"]`)
    8. `technical-design` (`agent`: `ForgeTechDesigner`, `dependsOn`: `["codebase-research", "ux-checkpoint"]`)
    9. `technical-checkpoint` (`human`: `Technical authority`, `dependsOn`: `["technical-design"]`)
    10. `fullstack-implementation` (`agent`: `ForgeFullstackWorker`, `dependsOn`: `["technical-checkpoint"]`)
    11. `fullstack-verification` (`code`: `forge-fullstack-verification`, `dependsOn`: `["fullstack-implementation"]`)
    12. `acceptance-checkpoint` (`human`: `Acceptance reviewer`, `dependsOn`: `["fullstack-verification"]`)

#### 2.3 Definition Seeding Helper / Service
- Add seed loader in `WorkflowService` or startup bean (or test utility) to ensure declarative session definitions from classpath resource folder can be loaded/imported on startup if missing.

---

### Component 3: Cockpit Vanilla JS Timeline Lane Rendering Adaptation

#### 3.1 `factory/dashboard/js/components/temporal-lanes.mjs`
- Review `classifyActorKind(step)`:
  - Verify that explicit `step.lane` or `step.responsibility.kind` (`"human" | "agent" | "code"`) takes precedence over name heuristics.
- Review `buildBlueprintLayout(phases, activeStepId)`:
  - Ensure `node.lane = actorKind` is passed through and step timing attributes (`durationMs`, `startedAt`, `endedAt`/`completedAt`) populated in projection steps are correctly captured.
- Review `renderTemporalLanes(layout, options)`:
  - Ensure HTML output correctly renders the 3 swimlanes (`lane-human`, `lane-agent`, `lane-code`) with step names, status states, and timing attributes.

#### 3.2 `factory/dashboard/js/components/workflow-card.mjs` & `gantt.mjs`
- Verify `workflow-card.mjs` lane count chips (`lane-chip-human`, `lane-chip-agent`, `lane-chip-code`) render accurately from `buildBlueprintLayout`.
- Verify `gantt.mjs` step attribution for `laneOf(step)` handles explicit `responsibility.kind` / `lane` appropriately.

---

## 3. Step-by-Step Execution Plan

### Step 1: Enrich Workflow Projection with Multi-Lane Attributes (Kotlin)
- **Files**:
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowProjection.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`
- **Changes**:
  - Add `"lane"`, `"startedAt"`, `"completedAt"`, `"durationMs"` to allowed `STEP_FIELDS` in `WorkflowProjection.kt`.
  - In `SessionRunService.persistProjection`, include `"lane"` (`step.responsibility.kind.wire`), `"responsibility"`, and execution timestamps in `projection["steps"]`.

### Step 2: Import & Sample Session Definition (Kotlin)
- **Files**:
  - `factory-service/src/main/resources/sessions/forge-story-fullstack-ux.json`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowDefinitionController.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
- **Changes**:
  - Commit sample declarative definition `forge-story-fullstack-ux.json` under classpath resources.
  - Verify definition import via `WorkflowDefinitionController` correctly parses, validates DAG/responsibilities via `WorkflowDefinitionValidator` / `SessionDefinitionValidator`, and persists in DB.

### Step 3: Frontend Cockpit Lane Rendering Verification & Adaptation (Vanilla JS)
- **Files**:
  - `factory/dashboard/js/components/temporal-lanes.mjs`
  - `factory/dashboard/js/components/workflow-card.mjs`
  - `factory/tests/test-projection-governance.mjs`
- **Changes**:
  - Ensure `classifyActorKind` in `temporal-lanes.mjs` prioritizes `step.lane` and `step.responsibility.kind`.
  - Ensure timing/durations and statuts (`pending`/`ready`/`running`/`completed`/`failed`/`blocked`/`waiting_human`) are properly handled in temporal lane layouts.
  - Add/update node unit tests in `factory/tests/test-projection-governance.mjs`.

### Step 4: Integration Tests & Verification (Kotlin & Node)
- **Files**:
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/SessionRunServiceIntegrationTest.kt` (or extend `SessionSequencerIntegrationTest.kt` / `WorkflowServiceIntegrationTest.kt`)
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowDefinitionControllerIntegrationTest.kt`
- **Changes**:
  - Integration test verifying import of `forge-story-fullstack-ux.json` via controller/service.
  - Integration test verifying `SessionRunService` generates projections carrying `lane`, `responsibility`, status, timestamps, and broadcasts SSE projection events.
  - Run full test suite: `./gradlew clean test` (in `factory-service`) and `node factory/tests/test-projection-governance.mjs`.

### Step 5: Update Roadmap & Documentation
- **Files**:
  - `plans/2026-09-27-factory-instrument.md`
- **Changes**:
  - Mark W8.4 as completed in `plans/2026-09-27-factory-instrument.md`, indicating W8 wave is fully complete and only W6b (Node cutover + legacy decommissioning) remains.

---

## 4. Verification & Criteria of Success

1. **Gradle Build & Tests**:
   - `cd factory-service && ./gradlew clean test` passes 100% across all subprojects (`factory-sdk`, `factory-verification-core`, `factory-service`).
2. **Node Cockpit Tests**:
   - `node factory/tests/test-projection-governance.mjs` passes 100%.
3. **Multi-Lane Projection Output**:
   - Projections served by `factory-service` include `"lane"` (`"agent"`, `"code"`, `"human"`), `"responsibility"`, and valid statuts/timestamps for timeline rendering.
4. **Declarative Import**:
   - Sample definition `forge-story-fullstack-ux.json` validates and imports cleanly via `WorkflowDefinitionController`.
5. **Roadmap**:
   - `plans/2026-09-27-factory-instrument.md` updated with W8.4 marked done.
