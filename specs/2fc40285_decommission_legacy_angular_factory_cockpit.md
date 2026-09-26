# Specification: Decommission Legacy Angular Factory Cockpit & Monolithic Index

## 1. Context & Objectives

As part of Wave 3 Step 2 (Final Decommissioning) of Milestone D (Autonomous Factory Cockpit), the legacy Angular Factory components in `libs/agentos-ui/` and the legacy monolithic `factory/dashboard/index.html` file must be removed. The new standalone vanilla JS/CSS cockpit (`cockpit.html`) has taken over all cockpit functionality.

Key Objectives:
1. Delete all legacy Factory Angular components, services, models, and test specs from `libs/agentos-ui/`.
2. Clean up Angular routing (`agentos.routes.ts`) and shell layout (`case-shell.component.ts` / `.html` / `.spec.ts`) by removing the Factory route, tab, shell view, and `<agentos-factory-runs>` component.
3. Remove `factory/dashboard/index.html` and update `factory/dashboard/composition-root.mjs` so GET `/` redirects (HTTP 302) to `/cockpit` (or serves `cockpit.html`), while preserving static assets (`/css/*`, `/js/*`) and `/cockpit`.
4. Re-run `node factory/toolchain/build.mjs` if needed to re-bundle the factory toolchain (do NOT touch the new vanilla cockpit assets in `factory/dashboard/js/`, `css/`, `cockpit.html`).
5. Ensure `libs/agentos-ui` compiles cleanly and all unit/integration tests pass (`pnpm nx test agentos-ui`).

---

## 2. Detailed Work Breakdown

### Task 1: Remove Legacy Factory Files in `libs/agentos-ui/`

Delete the following directories and files under `libs/agentos-ui/src/lib/`:

#### Components to Delete (`src/lib/components/`):
- `factory-forge-runs/` (including all sub-components: `epic-cockpit`, `story-run`, `delivery-panel`, `forge-activity-panel`, `forge-ribbon`, `forge.data.ts`, `forge.model.ts`, etc.)
- `factory-launch/`
- `factory-phase-panel/` (including `factory-phase-event-row`)
- `factory-run-detail/`
- `factory-run-timeline/`
- `factory-runs/`
- `factory-workflow-projection/` (including `factory-temporal-lanes`)

#### Services and Models to Delete (`src/lib/services/`):
- `factory-api.service.ts` and `factory-api.service.spec.ts`
- `factory-state.service.ts` and `factory-state.service.spec.ts`
- `factory-workflow-projection-state.service.ts` and `factory-workflow-projection-state.service.spec.ts`
- `factory-launch-state.service.ts` and `factory-launch-state.service.spec.ts`
- `factory-forge-state.service.ts`
- `factory-review-gate.service.ts` and `factory-review-gate.service.spec.ts`
- `forge-activity.service.ts`
- Associated models: `factory-delivery.model.ts`, `factory-operational-metrics.model.ts`, `factory-workflow-projection.model.ts`

---

### Task 2: Update Angular Wiring in `libs/agentos-ui/`

#### 1. `libs/agentos-ui/src/lib/agentos.routes.ts`:
- Remove the route entry for `path: 'factory'`.
- Remove imports related to `FactoryRunsComponent`.

#### 2. `libs/agentos-ui/src/lib/components/case-shell/case-shell.component.ts`:
- Update `ShellView` type definition: change `export type ShellView = 'cases' | 'factory'` to `export type ShellView = 'cases'` or remove `ShellView` / update references.
- Remove `FactoryRunsComponent` from the `imports` array.
- Update `activeView` computed signal: since `cases` is the only view, remove the `'factory'` check or simplify `activeView` / tab switching logic.
- Update `switchView` method and namespace selection handlers: remove setting `queryParams['view'] = 'factory'`.

#### 3. `libs/agentos-ui/src/lib/components/case-shell/case-shell.component.html`:
- Remove the `<button>` for "Factory" in `<nav class="case-shell__tabs">`. If "Cases" is the sole tab remaining, simplify or remove the tab bar if no longer needed, or keep `<nav class="case-shell__tabs">` with single tab if required for semantics.
- Remove `@if (activeView() === 'factory') { <agentos-factory-runs ... /> }`.

#### 4. `libs/agentos-ui/src/lib/components/case-shell/case-shell.component.spec.ts`:
- Remove or update tests that assert on `view=factory`, navigating to factory, and rendering `agentos-factory-runs`.

#### 5. Verify No Residual References in `libs/agentos-ui`:
- Perform a grep across `libs/agentos-ui/` for any leftover `factory-runs`, `factory-forge-runs`, `agentos-factory-`, `FactoryApiService`, `factory-*.service`, or `ShellView`.

---

### Task 3: Remove Legacy Dashboard `index.html` & Update HTTP Routing

#### 1. Delete `factory/dashboard/index.html`:
- File path: `factory/dashboard/index.html`

#### 2. Update `factory/dashboard/composition-root.mjs`:
- Remove unused `indexHtml` function (`const indexHtml = () => readFileSync(...)`).
- In `createHttpServer`:
  - Update GET `/` and GET `/index.html` handler:
    Change:
    ```javascript
    if (method === 'GET' && (path === '/' || path === '/index.html')) {
      res.writeHead(200, { ...corsHeaders, 'Content-Type': 'text/html; charset=utf-8', 'X-Correlation-Id': res.correlationId })
      return res.end(indexHtml())
    }
    ```
    To a HTTP 302 redirect to `/cockpit`:
    ```javascript
    if (method === 'GET' && (path === '/' || path === '/index.html')) {
      res.writeHead(302, { ...corsHeaders, 'Location': '/cockpit', 'X-Correlation-Id': res.correlationId })
      return res.end()
    }
    ```
- Ensure GET `/cockpit`, GET `/cockpit.html`, and static asset routes (`/css/*`, `/js/*`) are untouched and continue to work as expected.

#### 3. Update Dashboard Tests:
- Check `factory/tests/test-cockpit-shell.mjs` and other tests in `factory/tests/` that request `/index.html` or reference `index.html`. Update assertions so GET `/` / `/index.html` returns 302 redirect to `/cockpit` (or update test to follow redirect / check status 302).

---

### Task 4: Toolchain Build & Non-Regression Verification

#### 1. Toolchain Re-build:
- Execute `node factory/toolchain/build.mjs`.
- Confirm that NO modifications were made to `factory/dashboard/js/`, `factory/dashboard/css/`, or `factory/dashboard/cockpit.html`.

#### 2. Run Test Suites:
- Run Nx tests for `agentos-ui`:
  `pnpm nx test agentos-ui`
- Run factory dashboard test suite:
  `node --test factory/tests/test-cockpit-shell.mjs`

---

## 3. Acceptance Criteria

- [ ] All legacy Angular Factory components (`factory-forge-runs`, `factory-launch`, `factory-phase-panel`, `factory-run-detail`, `factory-run-timeline`, `factory-runs`, `factory-workflow-projection`) deleted.
- [ ] All legacy Factory services (`factory-api.service.ts`, `factory-state.service.ts`, `factory-workflow-projection-state.service.ts`, `factory-launch-state.service.ts`, `factory-forge-state.service.ts`, `factory-review-gate.service.ts`, `forge-activity.service.ts`) and associated models deleted from `libs/agentos-ui/`.
- [ ] Route `'factory'` and Factory tab removed from `agentos.routes.ts` and `case-shell`.
- [ ] Zero leftover references to Factory components/services in `libs/agentos-ui/`.
- [ ] File `factory/dashboard/index.html` removed.
- [ ] GET `/` and GET `/index.html` in `composition-root.mjs` redirect with 302 to `/cockpit`.
- [ ] `pnpm nx test agentos-ui` passes 100% green with zero failures.
