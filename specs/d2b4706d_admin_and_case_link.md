# Implementation Plan — Milestone D Wave 3 Step 1: `admin-and-case-link`

## Task Overview

Implement Milestone D Wave 3 Step 1 ("admin-and-case-link") for the Coday Factory Cockpit:
1. **Admin Artefacts View (`factory/dashboard/js/views/artifact-admin.mjs`)**: Mountable ESM view module for `#view-admin` consuming B5 artifact governance admin routes (`POST /api/factory/admin/artifacts/gc`, `POST /api/factory/admin/artifacts/:id/purge`, `POST /api/factory/admin/artifacts/:id/legal-hold`). Displays freed volume / purged count / legal hold state. Handles 403 `FORBIDDEN_ADMIN_REQUIRED` cleanly with unmasked escaped error messages. Confirms destructive actions via native `<dialog>` modal using `showModal`/`closeModal`. Adheres to view lifecycle contract (`mount`, `unmount`, `getState`, teardown hooks). Mounted in `app.mjs` router for `/admin`.
2. **Case Link Component (`factory/dashboard/js/components/case-link.mjs`)**: Safe ESM component for rendering case/thread links from a `controllerExecution` object. Enforces strict SSRF invariant (only constructs AgentOS deep links from trusted base `agentosUrl` from `GET /api/config` using `buildAgentosCaseUrl`; `coday-express` threads rendered as safe readable text, or clickable only if server configured `codayExpressUrl` is present in `/api/config`). Integrates additively with `workflow-card.mjs`, `projection.mjs`, and `run-detail.mjs`.
3. **OpenAPI Doc Update (`factory/dashboard/openapi.json`)**: Document the 3 admin routes, update version tag from `5.0.0-phase5` to reflect B5/B6 (e.g., `5.0.0-b6` or `6.0.0-b6`), and note unprefixed forge aliases as TODO if appropriate. No backend logic changes.
4. **Offline Test Suite (`factory/tests/test-cockpit-admin-and-case-link.mjs`)**: Complete offline Node.js test suite exercising Admin view rendering, 403 authorization error handling, GC/purge/legal-hold actions, modal dialog flows, mount/unmount lifecycle/teardown, `case-link` component rendering, and strict SSRF invariant checks. Ensures all offline cockpit tests pass with zero failures.

---

## Architectural Constraints & Invariants

- **Vanilla ESM**: All code in `factory/dashboard/` must remain pure Vanilla ESM with zero npm dependencies and zero build step.
- **SSRF Invariant**: Never construct URLs from untrusted user input without validating or using trusted base URL from `/api/config`. `buildAgentosCaseUrl` parses the trusted base URL with WHATWG `URL`, enforces `http:` or `https:`, rejects URLs with embedded credentials (`username`/`password`), and uses `encodeURIComponent` on case ids.
- **Auth Hardening & Non-trust of Client Headers**: Authorization is never done via client-supplied headers. When the backend returns `403 FORBIDDEN_ADMIN_REQUIRED`, the UI must properly escape and render the clear error message without swallowing or masking it.
- **Cockpit Lifecycle Contract**: Every view mounter exported via `mount` or `mount<Name>View` accepts `(container, options)` where options include `apiClient`, `onNavigate`, `registerTeardown`, etc., and returns a handle `{ unmount, render, getState, ... }` or a Promise resolving to it. `unmount` must abort pending requests, detach event listeners, clear timers, and empty the container.
- **UI Dockyard Styling**: Admin panel strictly uses existing CSS classes (`.panel`, `.chip`, `.btn`, `.btn-primary`, `.btn-danger`, `.form-group`, `.form-control`, `.cockpit-id`, etc.) and escaped DOM generation.
- **Strict Scope Boundaries**:
  - No edits to backend routes or operational JS.
  - No deletion of Angular components or legacy `index.html`.
  - Additive changes to existing frontend components (`workflow-card.mjs`, `projection.mjs`, `run-detail.mjs`, `app.mjs`).

---

## Step-by-Step Implementation Plan

### Step 1: Create Case Link Component (`factory/dashboard/js/components/case-link.mjs`)

Create `factory/dashboard/js/components/case-link.mjs`:
- Export helper function `buildCaseLinkHtml(controllerExecution, options)`:
  - Takes `controllerExecution` object (`{ kind, caseId, threadId, ... }`) and `options` (`{ agentosUrl, codayExpressUrl, class }`).
  - Helper `buildAgentosCaseUrl(caseId, agentosUrl)` (can import from `workflow-card.mjs` or encapsulate cleanly in `case-link.mjs` and re-export).
  - When `kind === 'agentos'` and `caseId` present:
    - If safe AgentOS href exists: `<a class="case-link ..." href="${escAttr(href)}" target="_blank" rel="noopener noreferrer" title="Ouvrir le case dans AgentOS">${escHtml(label)}</a>`
    - Fallback: `<span class="case-id cockpit-id" title="Lien AgentOS indisponible">${escHtml(label)}</span>`
  - When `kind === 'coday-express'` or `threadId` present:
    - If `codayExpressUrl` base URL provided in `options`: validate with safe URL builder and render `<a class="case-link ...">Thread ${escHtml(threadId)}</a>`
    - Otherwise: `<span class="thread-id cockpit-id" title="Identifiant de thread Coday">Thread ${escHtml(threadId)}</span>`
  - Default/generic fallback when `caseId` present without kind: `<span class="case-id cockpit-id">Case ${escHtml(caseId)}</span>`
- Export DOM creation helper `createCaseLinkElement(controllerExecution, options, doc)` returning an `HTMLElement` or `DocumentFragment`.
- Add defensive string and HTML escaping functions (`escHtml`, `escAttr`).

### Step 2: Additive Integration of `case-link.mjs` in Cockpit Views & Components

1. **`factory/dashboard/js/components/workflow-card.mjs`**:
   - Refactor internal `renderCaseIdentity` to delegate to `buildCaseLinkHtml(execution, options)` from `./case-link.mjs`.
   - Maintain full backwards compatibility with existing option parameter `options.agentosUrl`.
2. **`factory/dashboard/js/views/projection.mjs`**:
   - Ensure `projection.mjs` fetches or uses `agentosUrl` / `codayExpressUrl` from `/api/config` and passes options when rendering cards or workflow details.
3. **`factory/dashboard/js/views/run-detail.mjs`**:
   - Integrate `renderCaseLink` / `buildCaseLinkHtml` in the header or facts metadata section for governed run execution controller context.

### Step 3: Create Artifact Admin View (`factory/dashboard/js/views/artifact-admin.mjs`)

Create `factory/dashboard/js/views/artifact-admin.mjs`:
- **State Management**:
  - `state = { mounted: true, loading: false, error: null, adminEntitled: true, gcReport: null, purgeResult: null, legalHoldResult: null, artifactIdInput: '', purgeReasonInput: '', legalHoldArtifactId: '', legalHoldState: true, legalHoldReason: '' }`
  - Read `apiClient` from options.
- **Render Function**:
  - Renders UI panel inside `#view-admin` placeholder:
    - Title & Admin Status Header (`.panel-header`, `.chip` indicating admin status or 403 state).
    - If `error` with code `FORBIDDEN_ADMIN_REQUIRED` or status `403`: render a prominent escaped error banner (`.panel.panel-error` or `.cockpit-alert.alert-danger`) stating user lacks admin entitlement ("Accès refusé : Droits d'administration requis (FORBIDDEN_ADMIN_REQUIRED)").
    - Section 1: **Garbage Collection (GC)**:
      - Button: "Lancer GC" / "GC Dry-Run".
      - Result display: freed volume, scanned items, purged items count.
    - Section 2: **Purge Artifact**:
      - Inputs: Artifact ID text input, optional Reason text input.
      - Button: "Purger l'artefact" (`.btn-danger`).
      - On click: triggers native confirm dialog (`showModal`). Confirming calls `POST /api/factory/admin/artifacts/:id/purge` with `{ reason }`.
      - Result display: status, deleted size, message.
    - Section 3: **Legal Hold Governance**:
      - Inputs: Artifact ID text input, Legal Hold toggle (true/false checkbox or select), optional Reason.
      - Button: "Appliquer Legal Hold" (`.btn-primary`).
      - On click: calls `POST /api/factory/admin/artifacts/:id/legal-hold` with `{ legalHold: boolean, reason? }`.
      - Result display: updated legal hold state.
- **Modal Dialog Handling**:
  - Use `showModal(content, doc)` and `closeModal(doc)` from `app.mjs` (or construct DOM elements and attach event listeners to modal buttons).
- **HTTP Error Handling**:
  - Catch `ApiClientError`. If status is 403 / code is `FORBIDDEN_ADMIN_REQUIRED`, update `state.adminEntitled = false` and `state.error = err`, rendering explicit escaped error message (`err.message` / `err.code`).
- **Lifecycle Contract**:
  - `mountArtifactAdminView(container, options)`:
    - Attaches event listeners (submit, click, input).
    - Registers teardown function with `options.registerTeardown`.
    - Returns `{ unmount, render, getState, isMounted, runGc, purgeArtifact, setLegalHold }`.
  - `unmount`: sets `mounted = false`, detaches event listeners, clears container HTML.
  - Export `mount` alias for consistency with cockpit convention.

### Step 4: Wire Admin Route in `factory/dashboard/js/app.mjs`

In `factory/dashboard/js/app.mjs`:
- Import `mountArtifactAdminView` from `./views/artifact-admin.mjs`.
- Update `VIEW_MOUNTERS`:
  ```javascript
  export const VIEW_MOUNTERS = Object.freeze({
    '/launch': mountRunLaunchView,
    '/admin': mountArtifactAdminView,
  })
  ```
- Ensure `/admin` route in `ROUTES` resolves to `id: 'view-admin'`.

### Step 5: Update OpenAPI Documentation (`factory/dashboard/openapi.json`)

In `factory/dashboard/openapi.json`:
- Update `info.version` from `5.0.0-phase5` to `6.0.0-b6` (or `5.0.0-b6`).
- Add path definition for `POST /api/factory/admin/artifacts/gc`:
  - Request body: optional options (e.g. `{ dryRun?: boolean }`).
  - Responses: 200 (GC report schema), 403 (`FORBIDDEN_ADMIN_REQUIRED`), 500.
- Add path definition for `POST /api/factory/admin/artifacts/{id}/purge`:
  - Path parameter: `id` (artifactId).
  - Request body: `{ reason?: string }`.
  - Responses: 200 (purge success DTO), 403 (`FORBIDDEN_ADMIN_REQUIRED`), 404, 409, 500.
- Add path definition for `POST /api/factory/admin/artifacts/{id}/legal-hold`:
  - Path parameter: `id` (artifactId).
  - Request body: `{ legalHold: boolean, reason?: string }`.
  - Responses: 200 (updated artifact metadata DTO), 400 (`INVALID_LEGAL_HOLD`), 403 (`FORBIDDEN_ADMIN_REQUIRED`), 404, 500.
- Add comment or description noting unprefixed forge aliases as TODO/legacy where applicable.

### Step 6: Create & Execute Offline Test Suite (`factory/tests/test-cockpit-admin-and-case-link.mjs`)

Create `factory/tests/test-cockpit-admin-and-case-link.mjs`:
- Uses standard Node.js `assert/strict` and test harness structure (consistent with `test-cockpit-shell.mjs` and `test-cockpit-run-detail.mjs`).
- Test Scenarios:
  1. **`case-link.mjs` & SSRF Invariant Unit Tests**:
     - `kind: 'agentos'` with valid `agentosUrl`: builds valid `https://agentos.internal/case/123` href.
     - `kind: 'agentos'` with malformed/missing/credentialed `agentosUrl` (e.g., `http://user:pass@evil.com`): returns `null` href and unclickable span.
     - `kind: 'coday-express'` without `codayExpressUrl`: renders non-clickable `<span class="thread-id cockpit-id">Thread xyz</span>`.
     - `kind: 'coday-express'` with valid `codayExpressUrl`: renders safe `<a>` tag.
     - XSS safety: injection strings in `caseId` / `threadId` are properly escaped in output markup.
  2. **Workflow Card Case-Link Integration**:
     - Verify `renderWorkflowCard` produces proper markup incorporating `case-link.mjs`.
  3. **Artifact Admin View Rendering & Lifecycle**:
     - Mount view with mock container and mock `ApiClient`.
     - Test successful GC invocation: mock `apiClient.post('/api/factory/admin/artifacts/gc')` returns report data -> UI displays freed volume & count.
     - Test purge action with confirmation dialog: verify dialog display, action dispatch, success state rendering.
     - Test legal hold action: verify payload `{ legalHold: true, reason: 'audit' }` sent and UI updated.
     - Test 403 `FORBIDDEN_ADMIN_REQUIRED`: mock `apiClient` throwing `ApiClientError` status 403 -> UI renders escaped 403 error banner and disables admin action buttons.
     - Test unmount: verify event listeners detached and container emptied.
  4. **App Router Integration**:
     - Verify `VIEW_MOUNTERS['/admin']` is registered and can mount/unmount correctly.

Run tests via bash: `node factory/tests/test-cockpit-admin-and-case-link.mjs` and all other `test-cockpit-*.mjs` files to ensure zero regressions.

---

## Verification Plan

### Test Commands
1. Run new offline test suite:
   ```bash
   node factory/tests/test-cockpit-admin-and-case-link.mjs
   ```
2. Run existing cockpit offline test suites to ensure zero regressions:
   ```bash
   node factory/tests/test-cockpit-shell.mjs
   node factory/tests/test-cockpit-run-detail.mjs
   node factory/tests/test-cockpit-run-launch.mjs
   node factory/tests/test-cockpit-forge.mjs
   ```
3. Run monorepo lint check:
   ```bash
   pnpm lint
   ```

### Acceptance Checklist
- [ ] `factory/dashboard/js/views/artifact-admin.mjs` created and exported as ESM view module.
- [ ] `factory/dashboard/js/components/case-link.mjs` created and enforces SSRF invariants.
- [ ] `factory/dashboard/js/app.mjs` maps `/admin` in `VIEW_MOUNTERS`.
- [ ] `factory/dashboard/js/components/workflow-card.mjs`, `projection.mjs`, and `run-detail.mjs` additively updated to use `case-link.mjs`.
- [ ] `factory/dashboard/openapi.json` updated with B5/B6 admin endpoints and updated version string.
- [ ] `factory/tests/test-cockpit-admin-and-case-link.mjs` runs offline and passes all scenarios.
- [ ] All `test-cockpit-*.mjs` tests pass with 0 failures.