# Plan: Clean Sidebar Navigation & Wire `/workflows` Route in `cockpit-v2`

## Overview
This plan details the changes required in `apps/cockpit-v2` to clean up the navigation rail and ensure the `/workflows` route correctly loads the `AdminPageComponent` with updated breadcrumbs.

---

## Proposed Changes

### 1. Update Navigation Menu in Sidenav Rail
- **Files**:
  - `apps/cockpit-v2/src/app/layout/shell.component.ts`
  - `apps/cockpit-v2/src/app/layout/shell.component.html`
- **Changes**:
  - In `shell.component.ts`: Update `nav` array to contain EXACTLY 3 items:
    1. `{ label: 'Sandboxes', icon: 'grid_view', link: '/sandboxes' }`
    2. `{ label: 'Historique', icon: 'history', link: '/historique' }`
    3. `{ label: 'Workflows', icon: 'account_tree', link: '/workflows' }`
    - Remove "Sessions" (`/sessions/872641a8`) and "Agents" (`/agents`).
  - In `shell.component.html`:
    - Remove the hardcoded legacy "Réglages" link (`<a class="rail-item" routerLink="/reglages" ...>`) and its preceding `<span class="sf-spacer"></span>`.
    - Ensure the `@for (item of nav)` loop renders the 3 main navigation items cleanly.

### 2. Update Application Routing
- **File**:
  - `apps/cockpit-v2/src/app/app.routes.ts`
- **Changes**:
  - Add route for `workflows`:
    ```ts
    {
      path: 'workflows',
      loadComponent: () => import('./features/admin/admin-page.component').then((m) => m.AdminPageComponent),
    },
    ```
  - Redirect `reglages` to `workflows`:
    ```ts
    { path: 'reglages', redirectTo: 'workflows', pathMatch: 'full' },
    ```
  - Preserve `sessions/:runId`, `sandboxes`, `lancer`, `historique`, and wildcard `**` -> `sandboxes`.

### 3. Update Breadcrumb Label in `AdminPageComponent`
- **File**:
  - `apps/cockpit-v2/src/app/features/admin/admin-page.component.ts`
- **Changes**:
  - In constructor, change `inject(ShellState).crumbs.set([{ label: 'Gouvernance des artefacts' }])` to:
    ```ts
    inject(ShellState).crumbs.set([{ label: 'Workflows' }])
    ```
  - Keep all administrative and security logic (including `403` / `FORBIDDEN_ADMIN_REQUIRED` handling) intact.

### 4. Update Tests
- **File**:
  - `apps/cockpit-v2/src/app/features/admin/admin-page.component.spec.ts`
- **Changes**:
  - Update assertion in `it('sets the breadcrumb and fetches the workflow definitions on init', ...)`:
    - Change expected crumbs from `[{ label: 'Gouvernance des artefacts' }]` to `[{ label: 'Workflows' }]`.

---

## Verification Plan

### Automated Tests & Lint
Run the following commands:
- `pnpm nx test cockpit-v2`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`

### Manual Verification Matrix
1. Navigation Rail:
   - Check `nav` links in UI: strictly 3 items ("Sandboxes", "Historique", "Workflows").
   - Ensure "Sessions", "Agents", and "Réglages" are absent.
2. `/workflows` route:
   - Navigating to `/workflows` renders `AdminPageComponent`.
   - Breadcrumb displays "Workflows".
3. Redirect & Direct Routing:
   - Navigating to `/reglages` redirects to `/workflows`.
   - Direct navigation to `/sessions/:runId` continues to render `SessionPageComponent`.
