# Plan: Task Wave 2 (Salve A) - Projection Governance

## Overview
Implement the "projection-governance" feature of Milestone D (autonomous Factory Cockpit) as vanilla ESM modules in the dashboard client.
This wave provides the governed workflow projection list view in the Factory Cockpit, grouped by workstream case/ticket with collapsible sub-cases, temporal lanes by actor kind, workflow cards with safe link generation (protecting against SSRF), SSE reactivity with clean teardown, and lifecycle actions (`restore`, `remove`, `purge`) with native `<dialog>` confirmations.

---

## Boundaries & Constraints
- **Strict File Scope**: Create ONLY:
  1. `factory/dashboard/js/components/temporal-lanes.mjs`
  2. `factory/dashboard/js/components/workflow-card.mjs`
  3. `factory/dashboard/js/views/projection.mjs`
  4. `factory/tests/test-projection-governance.mjs`
- **Zero Modifications**: DO NOT modify or overwrite existing cockpit shell files: `cockpit.html`, `css/dockyard.css`, `js/app.mjs`, `js/services/api-client.mjs`, `js/services/sse-client.mjs`, `index.html`, `composition-root.mjs`, or any backend routes/services.
- **Zero External Dependencies**: Pure Vanilla ESM for browser files.
- **Local Helper Rule**: Define any internal helper locally inside component/view/test files to prevent conflicts.

---

## File Specifications & Technical Requirements

### 1. `factory/dashboard/js/components/temporal-lanes.mjs`
- **Purpose**: Render and calculate temporal lanes layout for a workflow timeline grouped by actor kind (`human`, `agent`, `code`).
- **Exported Function**: `buildBlueprintLayout(phases, activeStepId)`
  - Accepts `phases` (array of phase/step objects or definitions) and optional `activeStepId`.
  - Maps each phase/step to an actor kind based on its `responsibility.kind` or fallback rule:
    - `'human'`: `responsibility.kind === 'human'` or step name/id suggesting human interaction.
    - `'agent'`: `responsibility.kind === 'agent'` or default agent execution.
    - `'code'`: `responsibility.kind === 'code'` or deterministic oracle step.
  - Groups steps into 3 temporal lanes: `human`, `agent`, `code`.
  - Computes state per step: `'completed'`, `'active'`, `'pending'`, `'failed'` (comparing with step status or `activeStepId`).
  - Computes timing metadata (duration, estimated relative width/position for rendering timeline bars).
  - Returns structured layout object:
    ```js
    {
      lanes: {
        human: [ { id, name, status, state, actorKind, timing } ],
        agent: [ ... ],
        code: [ ... ]
      },
      summary: { totalSteps, activeStepId, completionRate }
    }
    ```
  - **Exported Render Helper**: `renderTemporalLanes(layout)` (or HTML string generator) to render the 3 swimlanes visually in DOM/HTML.

### 2. `factory/dashboard/js/components/workflow-card.mjs`
- **Purpose**: Component rendering individual workflow cards with status chips, timing info, progress, case links, and action triggers.
- **Exported Function**: `renderWorkflowCard(snapshot, options)`
  - `snapshot`: Workflow snapshot item returned from `GET /api/factory/workflows` (or single lookup), containing `workflowId`, `revision`, `projection` (title, status, steps), `controllerExecution` (`kind`, `caseId`, `threadId`, `agentId`), `relations` (`rootWorkflowId`, `parentWorkflowId`), lifecycle state (`active`, `removed`).
  - `options`: `{ agentosUrl, onAction }` (where `agentosUrl` is retrieved from `GET /api/config`).
- **Link Logic & Strict SSRF Invariant**:
  - **AgentOS Case Link**: If `controllerExecution.kind === 'agentos'` AND `caseId` is present:
    - If `agentosUrl` is a valid string, safely construct the link:
      ```js
      const href = new URL('/case/' + encodeURIComponent(caseId), agentosUrl).href
      ```
      Wrap in `<a href="${href}" target="_blank" rel="noopener noreferrer">...</a>`.
    - If `agentosUrl` is missing, untrusted, or invalid URL, do NOT render a clickable `<a>`. Fallback to plain text identifier `<span>Case: ${caseId}</span>`.
  - **Coday Express Identifier**: If `controllerExecution.kind === 'coday-express'` or `threadId` is used without URL factory:
    - Render an unclickable identifier text (e.g., `<span class="thread-id">Thread: ${threadId}</span>`).
    - NEVER generate a clickable link for `coday-express` thread identifiers.
- **Card Content**:
  - Title, `workflowId`, status badge/chip (`ready`, `running`, `completed`, `failed`, `removed`), lifecycle state.
  - Coday Case / Thread identifier (with SSRF-safe link if AgentOS).
  - Phase/progress summary (e.g. "3/5 steps completed") and temporal lane mini-preview or layout integration.
  - Timing info if available (`durationMs`, start/end dates).
  - Action buttons:
    - `restore`: button shown if status/state is `removed` or removed list view.
    - `remove`: button shown if active.
    - `purge`: button shown for hard deletion confirmation.

### 3. `factory/dashboard/js/views/projection.mjs`
- **Purpose**: Main view controller populating the `#view-projection` DOM container in `cockpit.html`.
- **Exported Function**: `mountProjectionView(container, options)` (or default export / router view function).
  - Arguments:
    - `container`: DOM element (`document.getElementById('view-projection')`).
    - `options`: `{ apiClient, sseClient, registerTeardown, namespaceId, getAgentosUrl }`.
- **View Lifecycle & State Management**:
  - Reads `namespaceId` (from query param, state, or default uuid).
  - Fetches configuration (`GET /api/config`) to acquire `agentosUrl`.
  - Fetches active workflows (`GET /api/factory/workflows?namespaceId=...&state=active`).
  - Toggles/tabs for viewing "Active" vs "Removed" (`GET /api/factory/workflows?namespaceId=...&state=removed`).
  - Supports targeted re-fetch of individual workflow (`GET /api/factory/workflows/:id`) on specific SSE updates.
- **SSE Stream Integration & Zero-Leak Guarantee**:
  - Connects to `/api/factory/workflows/stream?namespaceId=...` using `SseClient` (or reusing provided instance).
  - Listens to named events:
    - `workflow-projection-updated`: Targeted re-fetch of the affected `workflowId`, or full list refresh.
    - `workflow-projection-removed`: Update list state to move item to removed tab or remove from active list.
    - `workflow-projection-restored`: Update list state to return item to active list.
    - `workflow-projection-purged`: Hard remove item from local state.
  - **Teardown**: Registers a cleanup callback with `registerTeardown(() => { ... })` that closes SSE connection, removes all event listeners, and cancels pending async timers/fetches.
- **Grouping & Unfolding (Hierarchy)**:
  - Groups workflows by case/ticket (`controllerExecution.caseId` or snapshot `relations.rootWorkflowId`).
  - Renders collapsible groups (e.g., using `<details><summary>` or toggleable CSS classes).
  - Hierarchical sub-cases: Workflows with a `parentWorkflowId` or nested scope are grouped under their root/parent workflow card as sub-cases.
  - Un-grouped / orphaned workflows placed under a default group ("Autres workflows" / "Root").
- **Lifecycle Actions & Modal Confirmation**:
  - Clicking `Restore`, `Remove`, or `Purge` on a workflow card opens a modal using native `<dialog>` (`showModal` from `app.mjs` or DOM element `#cockpit-dialog`).
  - Modal displays clear warning and confirmation buttons (`Confirm`, `Cancel`).
  - On confirm, sends corresponding HTTP request:
    - Restore: `POST /api/factory/workflows/:id/restore?namespaceId=...`
    - Remove: `DELETE /api/factory/workflows/:id?namespaceId=...`
    - Purge: `DELETE /api/factory/workflows/:id/purge?namespaceId=...`
  - **Error Handling**: Handles HTTP 409 responses (`REVISION_CONFLICT`, `WORKFLOW_REMOVED`, `INVALID_LIFECYCLE_TRANSITION`) gracefully by displaying inline alert/toast feedback to the user and re-fetching current state.

### 4. `factory/tests/test-projection-governance.mjs`
- **Purpose**: Offline Node test runner (standalone executable: `node factory/tests/test-projection-governance.mjs`) validating all wave 2 requirements without a browser or real backend server.
- **Test Categories**:
  1. **Temporal Lanes Component**:
     - Tests `buildBlueprintLayout` step classification into `human`, `agent`, `code` lanes.
     - Tests status calculations and layout structure.
  2. **Workflow Card & SSRF Link Safety**:
     - Tests AgentOS case link generation with valid `agentosUrl` -> generates safe absolute URL.
     - Tests AgentOS case link with invalid/missing `agentosUrl` -> fallback to unclickable text.
     - Tests Coday Express thread identifier -> verifies plain text / unclickable `<span>`, no `<a href>`.
     - Validates URL parsing / sanitization against SSRF injection vectors (e.g. `javascript:`, malformed bases).
  3. **SSE Invalidation Handling**:
     - Simulates SSE events (`updated`, `removed`, `restored`, `purged`).
     - Verifies targeted re-fetch and state list updates.
  4. **Case Grouping & Hierarchy Unfolding**:
     - Feeds sample workflow snapshot DTOs with root/parent relations and `caseId`s.
     - Verifies correct grouping into parent case cards and collapsible sub-case lists.
  5. **Lifecycle Actions & 409 Conflict Handling**:
     - Mocks API responses for restore/remove/purge and 409 `REVISION_CONFLICT`.
     - Verifies lifecycle state updates and conflict feedback.
  6. **View Mount & Teardown Leak Prevention**:
     - Mounts view against a DOM double (e.g. mock DOM nodes).
     - Calls teardown and verifies 0 leftover SSE listeners or unhandled promises.
- Exits with code 0 on success, code 1 on failure.

---

## Plan Steps & Implementation Sequence

### Step 1: Build `factory/dashboard/js/components/temporal-lanes.mjs`
- Create module file.
- Implement `buildBlueprintLayout(phases, activeStepId)` and `renderTemporalLanes(layout)`.
- Export methods cleanly.

### Step 2: Build `factory/dashboard/js/components/workflow-card.mjs`
- Create module file.
- Implement SSRF-safe `buildAgentosCaseUrl(caseId, agentosUrl)`.
- Implement `renderWorkflowCard(snapshot, { agentosUrl, onAction })`.
- Return HTML or DOM Node with proper badges, links, progress, and lifecycle action buttons.

### Step 3: Build `factory/dashboard/js/views/projection.mjs`
- Create view module.
- Implement view mounting function `mountProjectionView(container, options)`.
- Implement state holding (active vs removed workflows, grouped by case/ticket).
- Wire API calls (`GET /api/config`, `GET /api/factory/workflows`, restore/remove/purge actions).
- Wire SSE stream subscriptions and targeted refetch.
- Implement modal confirmation flow for lifecycle actions using `<dialog>`.
- Register teardown logic.

### Step 4: Build `factory/tests/test-projection-governance.mjs`
- Create standalone node test runner using standard `node:assert/strict` and harness structure matching `test-cockpit-shell.mjs`.
- Mock lightweight DOM environment / double where needed.
- Add test scenarios for:
  - Temporal lanes layout.
  - Workflow card rendering & SSRF link protection.
  - Case grouping & collapsible sub-cases.
  - SSE events application & targeted refetch.
  - Lifecycle actions & 409 handling.
  - Teardown & memory leak checks.

### Step 5: Verification & Validation
- Run `node factory/tests/test-projection-governance.mjs`.
- Run `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`.
- Verify no touched/created files outside the 4 required paths.

---

## Verification Commands
```bash
# Run standalone projection governance offline test suite
node factory/tests/test-projection-governance.mjs

# Run standard repo test suite
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
```
