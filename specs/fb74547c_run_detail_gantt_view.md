# Plan: Factory Cockpit Wave 2 (salve A) run-detail-gantt View

## 1. Context & Boundaries

This plan details the implementation of Wave 2 (salve A) of the Factory Cockpit frontend: a pure Vanilla ESM `run-detail-gantt` view and supporting components operating strictly on **Governed Projection v2** data.

### Strict Perimeter Guidelines:
- **Base Authority**: Governed Projection v2 (`/api/factory/workflows*` and `/api/factory/workflows/:id/timing`).
- **Forbidden Operations**:
  - DO NOT use or wire into legacy JSONL / runs (`/api/runs`, `/api/factory/runs`, SSE line/done).
  - DO NOT touch existing cockpit shell files (`factory/dashboard/cockpit.html`, `factory/dashboard/css/dockyard.css`, `factory/dashboard/js/app.mjs`, `factory/dashboard/js/services/api-client.mjs`, `factory/dashboard/js/services/sse-client.mjs`). Consume them as-is.
  - DO NOT create list, projection-governance, forge, or admin views.
  - DO NOT modify server code, `server.mjs`, `composition-root.mjs`, backend files, or regenerate `factory-operational.mjs`.
- **Target Files to Create**:
  1. `factory/dashboard/js/components/facts.mjs`
  2. `factory/dashboard/js/components/gantt.mjs`
  3. `factory/dashboard/js/components/phase-panel.mjs`
  4. `factory/dashboard/js/views/run-detail.mjs`
  5. `factory/tests/test-cockpit-run-detail.mjs`

---

## 2. Component Design & Specifications

### 2.1 `factory/dashboard/js/components/facts.mjs`

**Purpose**: Classification and formatting helper functions for step/phase execution facts.

**Exports**:
- `FACT_GROUPS`: Array of group definitions (`{ title, keys }`) classifying step facts (e.g. Verdict, Fichiers, Tour d'agent, Contexte).
- `FLAGS`: Array of visual flag definitions (`{ key, when, icon, label, level }`) for step indicators (e.g. `wroteNothing`, `timedOut`, `killedByBudget`, `claimsMatch`, `missingFiles`).
- `emptySuccess(stepOrPhase)`: Helper returning boolean when a step completed with `pass` status but executed zero tasks (`tasks.executed === 0`).
- `renderFactGroups(facts)`: Formats facts into HTML blocks, grouping known keys and preserving unclassified keys under "Autres".
- `esc(str)`: Standard HTML escaping function for text output (`&`, `<`, `>`, `"`, `'`).

---

### 2.2 `factory/dashboard/js/components/gantt.mjs`

**Purpose**: Gantt timeline algorithm & strictly escaped HTML rendering for projection v2 workflow steps and timing.

**Data Mapping (v2 Projection & Timing DTOs)**:
- Input workflow object from `GET /api/factory/workflows/:id?namespaceId=...`: `{ workflowId, projection: { steps: [{ id, name, type, status, facts, startedAt, durationMs, ... }] }, ... }`
- Input timing object from `GET /api/factory/workflows/:id/timing?namespaceId=...`: `{ timing: { startedAt, totalElapsedMs, activeMs, steps: [{ stepId, startedAt, activeMs, ... }] } }`
- Steps are normalized so `step.name` falls back to `step.id` or `step.type`.
- Steps map to swimlanes via `laneOf(step)`:
  - If `step.type` or `step.phaseKind` is not `'agent'`, lane is `{ id: '__orchestrator', name: 'orchestrateur', kind: 'code' }`.
  - For agent steps, lane is determined by `facts.agentsSelected[0]` or `facts.agentName` or step name fallback.

**Core Algorithms Ported & Adapted**:
1. `laneOf(step)`: Assign step to lane object (`{ id, name, kind }`).
2. `timelineBounds(workflow, steps, timing, now)`: Compute `$t0$` start and duration span across all steps.
3. `buildGlobalTimeline(workflow, steps, timing, now)`: Compute warped positions, minimum gaps (2.5%), and ticks/anchors.
4. `layoutLaneBars(steps, timeline, workflow, now)`: Non-colliding row layout algorithm for bars in each lane.
5. `renderBar(step, layout, selectedStepId)`: Pure string or DOM generator for step bar button, **strictly HTML-escaped** (no unescaped `innerHTML` or raw interpolated step names/tooltips).
6. `buildTicks(span)`: Generates time axis tick marks.
7. `laneSubtitle(lane, steps)`: Lane summary label (e.g., "3 steps").
8. `collectFlags(step)`: Collects flag badges present in `step.facts`.

**HTML Safety Imperative**:
- Every dynamic string inserted into HTML output MUST be processed via `esc(str)` or built safely with DOM APIs (`document.createElement`).

---

### 2.3 `factory/dashboard/js/components/phase-panel.mjs`

**Purpose**: Detailed inspector panel for a selected step/phase.

**Features & Workflow**:
1. **Header & Badges**: Step name, step type badge (`code`/`agent`), status badge (`pass`, `fail`, `running`, `blocked`), duration chip, and flag chips (`wroteNothing`, `emptySuccess`, etc.).
2. **Facts Column**: Uses `renderFactGroups` from `facts.mjs` to render structured step facts.
3. **Right Column (Enrichment / Narrative)**:
   - Case Event Stream: If `step.facts.caseId` is present, fetches `GET /api/cases/:caseId/events` using `apiClient`. Degrades gracefully if 404/error/network failure with an informative notice ("AgentOS non disponible").
   - Jira Ticket View: If `step.facts.ticketId` is present, fetches `GET /api/factory/jira/:ticketId` using `apiClient`. Degrades gracefully if 404/error.
   - If neither is present, displays step execution/command facts summary.

---

### 2.4 `factory/dashboard/js/views/run-detail.mjs`

**Purpose**: Main view module mounting into `#view-detail`.

**Export Contract**:
- `mount(container, { workflowId, namespaceId, apiClient, sseClient })`:
  1. Fetches initial data via `apiClient`:
     - `GET /api/factory/workflows/:workflowId?namespaceId=...`
     - `GET /api/factory/workflows/:workflowId/timing?namespaceId=...`
     - `GET /api/factory/workflows/:workflowId/evidence?namespaceId=...` (optional enrichment)
     - `GET /api/factory/workflows/:workflowId/metrics?namespaceId=...` (optional enrichment)
  2. Subscribes to SSE live updates:
     - Listens to `WORKFLOW_PROJECTION_EVENTS.UPDATED` (`workflow-projection-updated`) on `sseClient`.
     - Filters events where `event.workflowId === workflowId` and `event.namespaceId === namespaceId`.
     - On match, re-fetches workflow & timing data and updates Gantt and Phase Panel seamlessly.
  3. Returns an unmount/teardown function `unmount()`:
     - Unsubscribes SSE listener.
     - Clears any pending auto-refresh timers or abort controllers.
     - Guarantees zero event listener/memory leaks.

---

### 2.5 `factory/tests/test-cockpit-run-detail.mjs`

**Purpose**: Offline test suite verifying all newly created components.

**Test Scenarios**:
1. **Gantt Algorithm Parity**:
   - Test deterministic lane assignment (`laneOf`), timeline bounds, warp timeline, and row collision layout for Projection v2 workflow steps and timing structure.
2. **Escaped Rendering & Security**:
   - Verify `renderBar` and step panel escape malicious step names (`<script>alert(1)</script>`), tooltips, and fact values, preventing HTML injection.
3. **Fact Classification**:
   - Verify `FACT_GROUPS`, `FLAGS`, and `emptySuccess()` classification behavior on pass steps with 0 tasks vs normal tasks.
4. **View Mount & Clean Unmount**:
   - Simulate mounting `run-detail.mjs` into a minimal DOM container (or mock container).
   - Verify initial API fetching and SSE subscription (`workflow-projection-updated`).
   - Call `unmount()` and verify SSE listener removal and zero lingering timers/handlers.

---

## 3. Implementation Steps

1. **Step 1: Create `factory/dashboard/js/components/facts.mjs`**
   - Implement `FACT_GROUPS`, `FLAGS`, `emptySuccess()`, `renderFactGroups()`, and `esc()`.

2. **Step 2: Create `factory/dashboard/js/components/gantt.mjs`**
   - Port Gantt algorithms from `index.html`.
   - Adapt data inputs for Projection v2 workflow and timing DTOs.
   - Enforce strictly escaped string template rendering.

3. **Step 3: Create `factory/dashboard/js/components/phase-panel.mjs`**
   - Implement step detail panel.
   - Implement asynchronous Jira and Case event enrichment with graceful error degradation.

4. **Step 4: Create `factory/dashboard/js/views/run-detail.mjs`**
   - Implement `mount` and `unmount` routines.
   - Wire `apiClient` requests for workflow, timing, evidence, and metrics.
   - Wire SSE `workflow-projection-updated` live refresh.

5. **Step 5: Create `factory/tests/test-cockpit-run-detail.mjs`**
   - Implement offline unit and integration tests.
   - Verify all test assertions pass cleanly.

6. **Step 6: Verification & Quality Checks**
   - Run `node factory/tests/test-cockpit-run-detail.mjs` (must pass with exit code 0).
   - Run `node factory/tests/test-cockpit-shell.mjs` (must pass with exit code 0).

---

## 4. Verification Commands

```bash
# Offline test for Wave 2 run-detail view
node factory/tests/test-cockpit-run-detail.mjs

# Shell test regression
node factory/tests/test-cockpit-shell.mjs
```
