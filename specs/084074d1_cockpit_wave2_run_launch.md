# Plan: Factory Cockpit Wave 2 Run Launch View & Route Wiring

## Summary
Implement the run launch view (`factory/dashboard/js/views/run-launch.mjs`) for the Factory Cockpit SPA and add minimal, additive route wiring in `factory/dashboard/js/app.mjs` and `factory/dashboard/cockpit.html` (if placeholder needed) to enable navigation and launching of governed workflow runs via `POST /api/factory/workflows/:id/run`. Verify with a comprehensive offline DOM-less test suite `factory/tests/test-cockpit-run-launch.mjs`.

## Architecture & Constraints
1. **Governed Routes Authority**:
   - Workflow launch MUST call `POST /api/factory/workflows/:id/run` with JSON payload `{ namespaceId, ticket? }` (and optional extra parameters if needed, maintaining backward compatibility).
   - Do NOT use legacy `POST /api/factory/runs` or JSONL formats.
2. **Navigation Flow**:
   - `/runs` (or `#view-runs` placeholder / launch view) -> user selects workflow/params -> submits form -> on success (200/201), route transitions to `/detail` (or `#view-detail`) passing `workflowId` and `namespaceId`.
3. **Immutability Constraints**:
   - MUST NOT modify `projection.mjs`, `run-detail.mjs`, or any component in `js/components/*.mjs`.
   - MUST NOT modify backend code, `composition-root.mjs`, or `index.html`.
   - Re-use existing services (`ApiClient`, `SseClient`, `showModal`/`closeModal`).
4. **Router & Layout Wiring**:
   - In `factory/dashboard/js/app.mjs`:
     - Update `ROUTES` (or ensure `/launch` / `/runs` placeholder handling) without breaking existing routes (`/runs`, `/detail`, `/projection`, `/forge`, `/admin`). Add `/launch` route entry if needed: `'/launch': { id: 'view-launch', label: 'Lancer' }` or wire `/runs` view mount to `RunLaunchView` / `mountRunLaunchView` while preserving all existing `ROUTES`.
     - In `cockpit.html`: ensure `<section id="view-launch" class="cockpit-view" aria-label="Lancer">` (or `<section id="view-runs"...>`) exists to host the form.
5. **UI / Styling**:
   - Native HTML form matching Dockyard CSS (`dockyard.css`), `.panel`, `.form-group`, `.mono`, `code`, `btn-primary`, `btn-danger`.
   - Clean mount / unmount lifecycle contract: returns `{ unmount: () => void }` (or handle function) that unsubscribes listeners/timers, preventing leaks.

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Create `factory/dashboard/js/views/run-launch.mjs`
Export `mountRunLaunchView` (and/or `mount`, `RunLaunchView`) compatible with cockpit view architecture.

- **Signature**:
  ```js
  export async function mountRunLaunchView(container, options = {}) {
    // options: { apiClient, onNavigate, namespaceId?, ... }
  }
  export const mount = mountRunLaunchView
  ```
- **Lifecycle & State**:
  - `workflowDefinitions`: Array fetched from `GET /api/factory/workflow-definitions` -> `.items` (or raw array).
  - `agents`: Array fetched from `GET /api/agents?namespaceId=${namespaceId}` when `namespaceId` is specified.
  - Handles loading state, error alerts (`.alert.alert-error` or `showModal`).
- **Form Fields**:
  - `workflowId` / Select workflow definition dropdown (`<select id="launch-workflow-id">`).
  - `namespaceId` (`<input id="launch-namespace-id" class="mono" required>`).
  - `ticket` optional Jira ticket key/number (`<input id="launch-ticket" class="mono">`).
  - Agent selection multi-select or checkbox list (`<select id="launch-agents">` or dynamic checkboxes when agents loaded).
  - Submit button (`<button type="submit" class="btn btn-primary">Lancer le workflow</button>`).
- **Submission Logic**:
  - Prevent default submit.
  - Read input values: `workflowId`, `namespaceId`, `ticket`.
  - Validate required fields (`workflowId`, `namespaceId`).
  - Send POST request via `apiClient.post(`/api/factory/workflows/${encodeURIComponent(workflowId)}/run`, { namespaceId, ticket: ticket || undefined })` or `apiClient.request`.
  - On **Success** (200 / 201):
    - Transition to `/detail` view with parameters `workflowId` and `namespaceId` (e.g. calling `options.onNavigate('/detail', { workflowId, namespaceId })` or setting `window.location.hash = `#/detail?workflowId=${workflowId}&namespaceId=${namespaceId}`).
  - On **Error**:
    - Gracefully handle `400` (`INVALID_RUN_REQUEST`), `409` conflict, and network errors.
    - Display clear error message in `.alert.alert-error` inside the container or via modal.
- **Teardown**:
  - Return `{ unmount: () => void }` which removes DOM event listeners and aborts any pending `fetch` / timers.

### Step 2: Wire Route and View in `factory/dashboard/cockpit.html` and `factory/dashboard/js/app.mjs`

- **`factory/dashboard/cockpit.html`**:
  - Add `<section id="view-launch" class="cockpit-view" aria-label="Lancer">` (or inside `view-runs`) containing the container `#view-launch-container` or `#view-launch`.
  - Add `<a href="#/launch" data-route="/launch">Lancer</a>` to `<nav class="cockpit-nav">` (or wire into `/runs`).
- **`factory/dashboard/js/app.mjs`**:
  - Import `mountRunLaunchView` from `./views/run-launch.mjs`.
  - Add `/launch` to `ROUTES`:
    ```js
    '/launch': { id: 'view-launch', label: 'Lancer' },
    ```
  - In `createRouter` / `mount`: when navigating to `/launch` (or `/runs` if mapped), call `mountRunLaunchView(doc.getElementById('view-launch'), { apiClient, onNavigate })` and register the returned `unmount` function with `registerTeardown()`.

### Step 3: Create Offline Test Suite `factory/tests/test-cockpit-run-launch.mjs`
Follow the exact DOM-less testing pattern established in `test-cockpit-shell.mjs`, `test-projection-governance.mjs`, and `test-cockpit-run-detail.mjs`.

- **Mock DOM Environment**:
  - Implement lightweight mock DOM element double (with `querySelector`, `querySelectorAll`, `addEventListener`, `removeEventListener`, `appendChild`, `replaceChildren`, `innerHTML`, `value`, `classList`, `setAttribute`).
- **Test Scenarios**:
  1. **Rendering**: `mountRunLaunchView` populates form fields (workflow selector, namespace input, ticket input, submit button).
  2. **Data Fetching**: Pre-fetches workflow definitions (`GET /api/factory/workflow-definitions`) and agents (`GET /api/agents?namespaceId=...`) correctly.
  3. **Form Submission (Success)**: Submits form data to `POST /api/factory/workflows/:id/run` with body `{ namespaceId, ticket }`, verifies 200/201 response, and checks navigation trigger to detail view.
  4. **Error Handling (400 & 409)**: Handles `400` (`INVALID_RUN_REQUEST`), `409` conflict, and 500 server errors, displaying user feedback without crashing.
  5. **Mount & Teardown**: Verifies clean unmount: zero event listener leaks, pending timers cleared, abort signals called.

### Step 4: Verification & Regression Testing
- Run new test: `node factory/tests/test-cockpit-run-launch.mjs`
- Run existing test suite:
  - `node factory/tests/test-cockpit-shell.mjs`
  - `node factory/tests/test-projection-governance.mjs`
  - `node factory/tests/test-cockpit-run-detail.mjs`
- Run full nx affected test suite if required: `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`

---

## Verification Plan

### Automated Tests
Execute the following commands in order:
1. `node factory/tests/test-cockpit-run-launch.mjs`
2. `node factory/tests/test-cockpit-shell.mjs`
3. `node factory/tests/test-projection-governance.mjs`
4. `node factory/tests/test-cockpit-run-detail.mjs`

### Success Criteria Check
- `factory/dashboard/js/views/run-launch.mjs` exists, exports `mountRunLaunchView` / `mount`.
- Route `/launch` wired in `app.mjs` and `cockpit.html`.
- Form submits exclusively to `POST /api/factory/workflows/:id/run`.
- All tests pass with zero failures.
- Zero modifications to `projection.mjs`, `run-detail.mjs`, `index.html`, `composition-root.mjs`, or backend files.
