# Plan: Cockpit V2 Layout Routes, FactoryApiService getWorkflowDefinition, and WorkflowsPageComponent

## Overview

This plan addresses:
1. Restoring nav bar and route mappings in `apps/cockpit-v2`:
   - Exact nav items in `ShellComponent`: Sandboxes (`/sandboxes`), Historique (`/historique`), Workflows (`/workflows`), Réglages (`/reglages`).
   - Route mapping in `app.routes.ts`: `/reglages` -> `AdminPageComponent`, `/workflows` -> `WorkflowsPageComponent`, preserving `/sessions/:runId` and `**`.
   - Restoring breadcrumb in `AdminPageComponent` constructor to `"Gouvernance des artefacts"`.
2. Adding `FullWorkflowStep`, `FullWorkflowDefinition`, and `getWorkflowDefinition` in `FactoryApiService` with tests in `factory-api.service.spec.ts`.
3. Implementing `WorkflowsPageComponent` (`workflows-page.component.ts`, `.html`, `.scss`, `.spec.ts`) as a standalone component using Angular signals, Material components, and directly consuming `FactoryApiService`.

---

## Proposed Changes

### Phase 1: Layout, Routes, and Admin Page Breadcrumb

#### 1. `apps/cockpit-v2/src/app/layout/shell.component.ts`
- Update `protected readonly nav: NavItem[]` to have EXACTLY these items in this order:
  - `{ label: 'Sandboxes', icon: 'grid_view', link: '/sandboxes' }`
  - `{ label: 'Historique', icon: 'history', link: '/historique' }`
  - `{ label: 'Workflows', icon: 'account_tree', link: '/workflows' }`
  - `{ label: 'Réglages', icon: 'settings', link: '/reglages' }`

#### 2. `apps/cockpit-v2/src/app/app.routes.ts`
- Update routes list:
  - `/reglages` -> `loadComponent: () => import('./features/admin/admin-page.component').then((m) => m.AdminPageComponent)`
  - `/workflows` -> `loadComponent: () => import('./features/workflows/workflows-page.component').then((m) => m.WorkflowsPageComponent)`
  - Retain `/sandboxes`, `/historique`, `/sessions/:runId`, `/lancer` (if existing), and fallback `{ path: '**', redirectTo: 'sandboxes' }`.

#### 3. `apps/cockpit-v2/src/app/features/admin/admin-page.component.ts`
- In constructor: change `ShellState.crumbs.set([{ label: 'Workflows' }])` to `ShellState.crumbs.set([{ label: 'Gouvernance des artefacts' }])`.
- Do NOT touch authorization behavior or admin logic (keep 403 / `FORBIDDEN_ADMIN_REQUIRED` intact).

#### 4. Update `apps/cockpit-v2/src/app/features/admin/admin-page.component.spec.ts`
- Update assertion checking the initial crumb from `'Workflows'` to `'Gouvernance des artefacts'`.

---

### Phase 2: FactoryApiService API Method & Interfaces

#### 1. `apps/cockpit-v2/src/app/core/factory-api.service.ts`
- Export interface `FullWorkflowStep`:
  ```ts
  export interface FullWorkflowStep {
    id: string
    name: string
    responsibility?: { kind?: string; name?: string }
    dependsOn?: string[]
    [key: string]: unknown
  }
  ```
- Export interface `FullWorkflowDefinition`:
  ```ts
  export interface FullWorkflowDefinition {
    schemaVersion?: string
    workflowType?: string
    version?: string
    title?: string
    trustedExecution?: boolean
    steps?: FullWorkflowStep[]
    [key: string]: unknown
  }
  ```
- Implement method `getWorkflowDefinition(workflowType: string, version: string, namespaceId?: string): Observable<FullWorkflowDefinition>`
  - URL path: `/api/factory/workflow-definitions/${encodeURIComponent(workflowType)}/${encodeURIComponent(version)}`
  - Call `this.request<unknown>(path, {}, namespaceId)` (which automatically attaches `X-Correlation-Id` and optional `X-Namespace-Id` / `namespaceId` param, and unwraps `{ data }`).
  - Defensively normalize payload with `.pipe(map(payload => isEnvelope(payload) ? payload.data : payload))`: handle payloads that might still be double-wrapped or direct objects. Ensure return type is `FullWorkflowDefinition`.

#### 2. `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`
- Add unit tests for `getWorkflowDefinition`:
  - Test GET request to `/api/factory/workflow-definitions/my-wf/v1` with proper encoding and unwrapping.
  - Test passing optional `namespaceId`.
  - Test defensive payload normalization when receiving direct object vs envelope `{ data: ... }`.

---

### Phase 3: WorkflowsPageComponent Implementation

#### Location
- `apps/cockpit-v2/src/app/features/workflows/workflows-page.component.ts`
- `apps/cockpit-v2/src/app/features/workflows/workflows-page.component.html`
- `apps/cockpit-v2/src/app/features/workflows/workflows-page.component.scss`
- `apps/cockpit-v2/src/app/features/workflows/workflows-page.component.spec.ts`

#### Component Design & Functionality
- **Standalone Component**, `ChangeDetectionStrategy.OnPush`.
- **Imports**: `CommonModule` (or Angular control flows `@for`, `@if`), Angular Material components (`MatCardModule`, `MatExpansionModule` / `MatAccordion`, `MatIconModule`, `MatProgressSpinnerModule`, `MatButtonModule`), `StatusChipComponent` (if useful), `UsdPipe` or other shared pipes if needed. Design tokens (`sf-*`).
- **Constructor**: Inject `ShellState` and set `crumbs` to `[{ label: 'Workflows' }]`.
- **Dependencies**: Inject `FactoryApiService` directly (do NOT modify or inject `FactoryStore`).
- **State Signals**:
  - `definitions = signal<WorkflowDefinition[]>([])`
  - `loading = signal(false)`
  - `error = signal<string | null>(null)`
  - `selectedKey = signal<string | null>(null)` (e.g. `type@version`)
  - `detailLoadingMap = signal<Record<string, boolean>>({})`
  - `detailErrorMap = signal<Record<string, string | null>>({})`
  - `detailsMap = signal<Record<string, FullWorkflowDefinition>>({})`
- **Lifecycle / On Init**:
  - Load definition list via `api.getWorkflowDefinitions()`.
  - Handle list response defensively (e.g., array or `{ items: [...] }`).
  - Graceful error handling on failure: set `error` signal to friendly error banner text (no app crash, no fake mocks).
- **Template Rendering**:
  - Breadcrumb is handled globally by shell.
  - Page layout using `sf-*` design tokens matching Cockpit V2 style.
  - Global loading spinner while loading definitions list.
  - Global error banner if `error()` is present.
  - Empty state message if `definitions()` array is empty.
  - Grid or list of cards for workflow definitions:
    - Card Title: `definition.title` if non-blank, else `definition.workflowType`.
    - Subtitle: `definition.workflowType@definition.version`.
    - Short definition hash: truncated `definitionHash` (e.g. first 8-12 characters or full styled chip).
    - Interactivity: Click card / expand panel to load full definition details.
  - Card Details (on click / expand):
    - Check if detail for key (`workflowType@version`) is already cached in `detailsMap()`; if not, set loading state in `detailLoadingMap()` and call `api.getWorkflowDefinition(workflowType, version)`.
    - Handle per-card loading state (spinner inside panel/card) and per-card error state (`detailErrorMap()`).
    - Render `steps` list when loaded:
      - For each step: `id`, `name`, `responsibility.kind` + `responsibility.name` (e.g. `responsibility?.kind`: `responsibility?.name`), and `dependsOn` (as badges or joined list).
      - Graceful display if steps are missing or empty.

#### Unit Tests (`workflows-page.component.spec.ts`)
- Use `TestBed` with mocked `FactoryApiService` and `ShellState`.
- Verify breadcrumb set to `[{ label: 'Workflows' }]`.
- Test initial rendering of definition cards from `getWorkflowDefinitions()`.
- Test clicking/selecting a definition card triggers `getWorkflowDefinition(type, version)` and renders step details (`id`, `name`, `responsibility`, `dependsOn`).
- Test error handling when `getWorkflowDefinitions()` fails (renders error banner).
- Test error handling when `getWorkflowDefinition()` fails for a card (renders detail error banner inside card).

---

## Verification Plan

### Automated Tests
1. Run `nx test cockpit-v2` to verify all unit tests pass, including:
   - `admin-page.component.spec.ts`
   - `factory-api.service.spec.ts`
   - `workflows-page.component.spec.ts`
2. Run `nx lint cockpit-v2` or `pnpm lint` to ensure no lint regressions.
3. Run `nx build cockpit-v2` to ensure build succeeds.

### Manual Verification / Sanity Check
- Check route structure in `app.routes.ts` and nav items in `shell.component.ts`.
