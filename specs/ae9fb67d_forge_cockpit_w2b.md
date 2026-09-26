# Plan — Wave 2 (salve B) « forge-cockpit » for Milestone D (Autonomous Factory Cockpit)

## Executive Summary
This plan delivers full functional parity of the Angular `factory-forge-runs` console (`epic-cockpit`, `story-run`, `delivery-panel`, `forge-activity-panel`, `forge-ribbon`) in vanilla ESM within `/work/app/factory/dashboard/js/`, adhering strictly to Dockyard styling, zero dependencies, zero build step, and leak-free mount/unmount lifecycles.

## Affected Files & Target Architecture

### Files to Create:
1. `factory/dashboard/js/components/forge-activity.mjs`
   - Real-time agent live activity feed component using Server-Sent Events (SSE).
   - Establishes SSE connection to `GET /api/cases/:caseId/events` using `SseClient` or EventSource.
   - Listens to events: `MessageEvent`, `TextChunkEvent` (for streaming text), `CaseStatusEvent` (status updates: RUNNING, IDLE, PENDING, KILLED, ERROR), `AgentSelectedEvent`, `AgentRunningEvent`, `AgentFinishedEvent`, `ToolRequestEvent`, `ToolResponseEvent`, `IntentionGeneratedEvent`, `ThinkingEvent`, `WarnEvent`, `ErrorEvent`, `QuestionEvent`, `AnswerEvent`.
   - Idempotent `close()` / `disconnect()` method detaching all listeners, closing SSE, resetting connection state with zero leaks.
   - Exports `mountForgeActivity(container, options)` and/or `ForgeActivityFeed` class.

2. `factory/dashboard/js/components/delivery-panel.mjs`
   - Delivery pipeline synthesis component rendering pipeline stages (`implementation-ready`, `artifact-ready`, `release-approved`, `deployed`, `production-verified`) and operations (`checkpoint`, `push`, `pull-request`, `promote`).
   - Fetches snapshot via `GET /api/factory/workflows/:id/delivery` using `ApiClient` with headers `X-Factory-Namespace-Id` and `X-Factory-Case-Id`.
   - Supports triggering delivery operations via `POST /api/factory/workflows/:id/delivery/{checkpoint,push,pull-request,promote}` when requested.
   - Strictly sanitizes external URLs (e.g. GitHub PR URLs) via `trustedUrl(url)` to `https://github.com` or `www.github.com`.
   - HTML escaping (`esc`) for all dynamic values.

3. `factory/dashboard/js/views/forge-cockpit.mjs`
   - Console view backing the `#/forge` route (filling `#view-forge`).
   - Pure ESM component with 4-screen state machine:
     - `streams`: workstream list, statuses, counts, flags (blocked, waiting human, done) + new workstream trigger.
     - `workstream`: selected workstream details, epics list, documents tab (`epics` / `docs`).
     - `epic`: epic details, stories progress, G1 gate approval panel.
     - `story`: story details with 10-step / US-steps timeline (`grooming`, `g1`, `spec`, `g2`, `code`, `g3`, `deploy`, `g4`, `merge`), detail sub-panels, and real-time activity feed integration.
   - Consumes `GET /api/factory/forge/runs?namespaceId=...` for Forge runs.
   - Consumes sub-routes: `runs/:epic/stories/:story/{executions,oracles,edits}`, `gates/G1`, `gates/G2`.
   - **Gate G1 Approval Constraint**:
     - Triggers `POST /api/factory/forge/runs/:runId/gates/G1/decision?namespaceId=...`
     - Body MUST NOT contain `actorId` or `authorityId`. Payload: `{ gate: 'G1', attempt: 1, policyVersion: 'forge-g1-human-v1', evidenceSetHash, outcome: 'approved', reasonCode: 'intent_confirmed' }`.
   - Complete lifecycle: `mount(container, options)` returns `{ unmount(), refresh(), selectScreen(s), ... }` teardown hooks closing activity SSE streams, timers, listeners cleanly.

4. `factory/tests/test-cockpit-forge.mjs`
   - Node-based offline test suite verifying:
     1. Screen navigation state machine (`streams`, `workstream`, `epic`, `story`).
     2. SSE event stream processing in `forge-activity.mjs` (`TextChunkEvent`, `CaseStatusEvent`, `MessageEvent`, `ToolRequestEvent`, `ToolResponseEvent`, `IntentionGeneratedEvent`).
     3. Gate G1 decision POST request payload verification (asserting absence of `actorId` and `authorityId`).
     4. `delivery-panel.mjs` stage calculation, URL sanitization, and API operation triggers.
     5. Lifecycle mount / unmount leak-free verification (ensuring SSE sockets & timers close cleanly).

### Files to Modify (Additive Only):
5. `factory/dashboard/js/app.mjs`:
   - Import `mount as mountForge` from `./views/forge-cockpit.mjs`.
   - Register route handler for `/forge` inside `createRouter` / `mount` logic or `bootstrapCockpit` without modifying existing route constants structure.

### STRICT OUT OF SCOPE / DO NOT TOUCH:
- `factory/dashboard/js/views/projection.mjs`
- `factory/dashboard/js/views/run-detail.mjs`
- Wave A components: `gantt.mjs`, `phase-panel.mjs`, `facts.mjs`, `workflow-card.mjs`, `temporal-lanes.mjs`
- `factory/dashboard/js/components/run-launch.mjs`
- `index.html`, `cockpit.html`, `composition-root.mjs`, backend files (`forge-routes.mjs`, `server.mjs`, etc.).

---

## Detailed Component Specifications

### 1. `factory/dashboard/js/components/forge-activity.mjs`
#### Model & Constants
- Event types: `MessageEvent`, `TextChunkEvent`, `CaseStatusEvent`, `AgentSelectedEvent`, `AgentRunningEvent`, `AgentFinishedEvent`, `ToolRequestEvent`, `ToolResponseEvent`, `IntentionGeneratedEvent`, `ThinkingEvent`, `WarnEvent`, `ErrorEvent`, `QuestionEvent`, `AnswerEvent`.
- Internal state:
  - `activeCaseId`, `events` array, `caseStatus` ('IDLE'|'RUNNING'|'PENDING'|'KILLED'|'ERROR'), `streamingText`, `connected` boolean.

#### Implementation Details
- Export `ForgeActivityStream` class or `createForgeActivityStream(caseId, options)` factory.
- Methods:
  - `connect(caseId, namespaceId)`: Opens SSE to `/api/cases/${caseId}/events`. Reuses `SseClient` or direct `EventSource`.
  - `disconnect()`: Detaches all listeners, calls `.close()`, resets state.
  - `renderFeed(events, streamingText, status)`: HTML builder formatted with Dockyard styling (`.forge-activity-panel`, `.feed-item`, `.status-badge`).
  - `mountForgeActivity(container, { caseId, namespaceId, apiClient, basePath })`: Mounts live feed into container, wires click handlers, returns `{ unmount(), getEvents(), getStatus() }`.

---

### 2. `factory/dashboard/js/components/delivery-panel.mjs`
#### Model & Constants
- Delivery stages: `['implementation-ready', 'artifact-ready', 'release-approved', 'deployed', 'production-verified']`.
- External URL Sanitizer:
  ```js
  export function trustedUrl(url) {
    try {
      const parsed = new URL(url)
      return parsed.protocol === 'https:' && ['github.com', 'www.github.com'].includes(parsed.hostname)
        ? parsed.toString()
        : null
    } catch {
      return null
    }
  }
  ```

#### Implementation Details
- `renderDeliveryPanel(deliverySnapshot, { loading, error })`:
  - Renders 5-step progress bar / stage indicator.
  - Renders active operations list (checkpoint, push, PR, promote).
  - Renders unresolved indeterminate flags and rollback requests.
  - Formats timestamps and safely escapes all strings (`esc`).
- `mountDeliveryPanel(container, { workflowId, namespaceId, caseId, apiClient })`:
  - Fetches snapshot from `GET /api/factory/workflows/${workflowId}/delivery` passing `X-Factory-Namespace-Id` and `X-Factory-Case-Id` headers via `ApiClient`.
  - Renders panel.
  - Provides trigger buttons for operations: `POST /api/factory/workflows/${workflowId}/delivery/${op}`.

---

### 3. `factory/dashboard/js/views/forge-cockpit.mjs`
#### Model & Navigation
- STEPS definitions (10 steps: `discovery`, `grooming`, `g1`, `spec`, `g2`, `code`, `g3`, `deploy`, `g4`, `merge`).
- US_STEPS definitions (9 steps: excluding `discovery`).
- Tone definitions (`TONES`) mapping `RunState` (`done`, `running`, `human`, `review`, `blocked`, `failed`, `prior`, `stopped`, `pending`, `na`) to CSS colors and labels.
- Screen Navigation:
  - Query parameters or local state: `ws` (workstream slug), `epic` (epic run ID), `story` (story run ID).
  - Screen derived state: `story` if story ID set; `epic` if epic ID set; `workstream` if ws set; else `streams`.

#### Views / Render Functions
1. **Workstreams List (`streams`)**:
   - List of workstreams with badge counts (total, blocked, waiting human, completed).
   - Form to create new workstream.
2. **Workstream Detail (`workstream`)**:
   - Tab switcher: `epics` vs `docs`.
   - List of epics within selected workstream, status badges, progress bars.
3. **Epic Detail (`epic`)**:
   - Ribbon of US runs and their step progression.
   - Gate G1 approval panel if G1 is pending human approval.
   - Triggers `POST /api/factory/forge/runs/${epicId}/gates/G1/decision?namespaceId=${ns}`.
   - **MUST NOT** send `actorId` or `authorityId` in the body.
4. **Story Detail (`story`)**:
   - 10-step / 9-step timeline (Grooming -> Merge).
   - Detailed sub-panels: executions, oracles, edits.
   - Mounted `forge-activity` panel for the active story case.
   - Mounted `delivery-panel` for story delivery status.

#### Lifecycle Contract
- `mount(container, options)`:
  - Loads forge runs list `GET /api/factory/forge/runs?namespaceId=${namespaceId}`.
  - Renders UI based on initial route/state.
  - Handles sub-component mounting (ForgeActivity, DeliveryPanel).
  - Returns `{ unmount(), refresh(), setScreen(screen, params) }`.
  - `unmount()` cleanly closes activity SSE streams, aborts pending fetch requests, and removes event listeners.

---

### 4. `factory/dashboard/js/app.mjs` (Additive Wire-up)
- Import `mount as mountForge` from `./views/forge-cockpit.mjs`.
- In `createRouter` mount phase for `/forge`:
  ```js
  if (route === '/forge') {
    const container = doc.getElementById('view-forge')
    if (container) {
      const handle = await mountForge(container, { namespaceId, apiClient, sseClient })
      registerTeardown(() => handle?.unmount())
    }
  }
  ```

---

### 5. Test Suite (`factory/tests/test-cockpit-forge.mjs`)
Offline Node.js test script running via `node factory/tests/test-cockpit-forge.mjs`.

Test Scenarios:
1. **Screen Navigation & View Rendering**:
   - Verify screen state transitions between `streams`, `workstream`, `epic`, and `story`.
2. **Forge Activity SSE Parsing & Cleanup**:
   - Feed mock SSE events (`MessageEvent`, `TextChunkEvent`, `CaseStatusEvent`, `ToolRequestEvent`, `IntentionGeneratedEvent`) to `ForgeActivityStream`.
   - Verify state updates, streaming text accumulation, status changes, and verify `disconnect()` closes EventSource and clears listeners.
3. **G1 Decision Request Structure**:
   - Intercept/mock `apiClient.request`.
   - Trigger G1 decision submit in `forge-cockpit.mjs`.
   - Assert body equals `{ gate: 'G1', attempt: 1, policyVersion: 'forge-g1-human-v1', evidenceSetHash: ..., outcome: 'approved', reasonCode: 'intent_confirmed' }` and **does NOT contain `actorId` or `authorityId`**.
4. **Delivery Panel Stage Calculation & URL Sanitization**:
   - Verify `trustedUrl()` rejects non-github URLs (`http://evil.com`, `https://phishing.com`) and accepts valid GitHub PR URLs.
   - Verify stage progress index calculation.
5. **Mount / Unmount Teardown Verification**:
   - Mount `forge-cockpit` view, mount `forge-activity` and `delivery-panel`.
   - Call `unmount()`. Assert all SSE connections closed, timers cleared, zero memory leaks.

---

## Verification Plan

### Test Command
```bash
node factory/tests/test-cockpit-forge.mjs
```

### Full Test Suite Execution
```bash
node factory/tests/test-cockpit-shell.mjs && node factory/tests/test-cockpit-run-detail.mjs && node factory/tests/test-cockpit-forge.mjs
```
