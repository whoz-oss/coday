# Plan: Cockpit Runs Full-Width & Nav Adjustments

## Overview
Perform two targeted visual/navigation adjustments on the Factory vanilla ESM cockpit (`factory/dashboard/`, served by `factory-service` on port 8141):
1. **Full-width run cards (Adjustment 1)**: Display run cards in a single full-width column layout mirroring Coday Dockyard (`.runs { display: grid; gap: 18px; }` without grid template multi-columns), keeping container centering (`main.cockpit-main` max-width ~1240px/1320px).
2. **Remove "Lancer" and "Forge" navigation tabs (Adjustment 2)**: Remove "Lancer" (`#/launch`) and "Forge" (`#/forge`) from topbar nav links and route definitions/mounters, redirecting any direct accesses to `/runs` (`#/runs`), while preserving existing views `/runs`, `/detail`, and `/admin`.

---

## Targeted Files & Modifs

### 1. `factory/dashboard/css/dockyard.css`
- Update `.runs` styling (line 935):
  - Remove `grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));`.
  - Keep `display: grid; gap: 18px;` so cards span 100% of the content container width and stack vertically in a single column.
  - Check `.cockpit-main` max-width (currently `1240px`, centered with `margin: 0 auto;`). Ensure reasonable container width (e.g., 1240px-1320px) remains centered.

### 2. `factory/dashboard/cockpit.html`
- Update topbar nav (`<nav class="cockpit-nav">`):
  - Remove `<a href="#/launch" data-route="/launch">Lancer</a>`
  - Remove `<a href="#/forge" data-route="/forge">Forge</a>`
  - Keep `<a href="#/runs" data-route="/runs">Runs</a>` and `<a href="#/admin" data-route="/admin">Admin</a>`.
- Remove static section elements `#view-launch` and `#view-forge` from `<main id="cockpit-view-container">` to eliminate dead DOM nodes (or keep them if required by structural queries, but removing unused `#view-launch` / `#view-forge` sections is cleaner as their routes are removed).

### 3. `factory/dashboard/js/app.mjs`
- **ROUTES**:
  - Remove `'/launch'` and `'/forge'` entries from `ROUTES`.
  - Retain `'/runs'`, `'/detail'`, `'/projection'` (alias for `/runs`), and `'/admin'`.
- **VIEW_MOUNTERS**:
  - Remove `'/launch': mountRunLaunchView` from `VIEW_MOUNTERS`.
- **Imports**:
  - Remove unused imports `mountRunLaunchView` and `mountForgeCockpit`.
- **`bootstrapCockpit` / `onMount`**:
  - Remove the route handler block for `if (route !== '/forge') return ...` / `/forge` mount logic.
- **Hash parsing / Fallback routing**:
  - `parseHash` will automatically resolve removed routes like `#/launch` or `#/forge` to `DEFAULT_ROUTE` (`'/runs'`), effectively redirecting legacy URLs cleanly without breaking the router.

---

## Step-by-Step Implementation Instructions

### Step 1: Update Run Cards CSS
File: `factory/dashboard/css/dockyard.css`
```css
.runs {
  display: grid;
  gap: 18px;
}
```
Remove `grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));`.

### Step 2: Remove Nav Links & Sections in HTML
File: `factory/dashboard/cockpit.html`
- In `<nav class="cockpit-nav">`, delete the links for `/launch` and `/forge`.
- In `<main id="cockpit-view-container">`, remove `<section id="view-launch">` and `<section id="view-forge">`.

### Step 3: Update Cockpit Router & Mounters
File: `factory/dashboard/js/app.mjs`
- Remove `mountRunLaunchView` and `mountForgeCockpit` imports.
- In `ROUTES`, remove `/launch` and `/forge`.
- In `VIEW_MOUNTERS`, remove `/launch`.
- In `bootstrapCockpit`'s `onMount`, remove the `/forge` initialization block.

### Step 4: Verification & Test Execution
- Run existing node unit tests: `node --test factory/dashboard/js/components/temporal-lanes.test.mjs`
- Run Nx affected test check: `pnpm nx test factory-service`
- Verify cockpit static assets in browser / HTTP server (`http://127.0.0.1:8141/`):
  - Nav bar shows only **Runs** and **Admin**.
  - Runs page displays single-column full-width run cards.
  - Clicking a run opens `/detail` with SSSF timeline intact.
  - Navigating to `#/launch` or `#/forge` redirects gracefully to `#/runs`.

---

## Verification Criteria
- No `#/launch` or `#/forge` links exist in topbar or DOM.
- `.runs` container has no multi-column `grid-template-columns` property.
- Single column stacked layout for run cards.
- Tests pass (`node --test ...` and `pnpm nx test factory-service`).
