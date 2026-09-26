# Plan: Rebase `sbx/coday-forge-cockpit-7d56` onto `origin/integration/jalonD-cockpit-w2-a` and Resolve Router Conflicts

## Overview
The sandbox branch `sbx/coday-forge-cockpit-7d56` needs to be rebased onto `origin/integration/jalonD-cockpit-w2-a` (the remote integration branch for jalonD-cockpit-w2). During this rebase, potential conflicts in `factory/dashboard/js/app.mjs` must be resolved carefully to preserve all route definitions and view initialization logic from both branches. All other cockpit files must remain unconflicted, and all dashboard tests must pass cleanly post-rebase.

## Pre-Rebase Checks
- Branch `sbx/coday-forge-cockpit-7d56` is currently checked out with a clean working tree.
- Remote tracking branch `origin/integration/jalonD-cockpit-w2-a` exists and is up to date.
- Tests currently pass:
  - `test-cockpit-shell.mjs`
  - `test-cockpit-forge.mjs`
  - `test-cockpit-run-detail.mjs`
  - `test-projection-governance.mjs`

## Execution Steps

### 1. Perform Git Rebase
- Run `git rebase origin/integration/jalonD-cockpit-w2-a`.

### 2. Resolve Conflicts in `factory/dashboard/js/app.mjs`
If conflicts occur in `factory/dashboard/js/app.mjs`:
- Inspect both sides of the conflict.
- **Route Registrations (`ROUTES` object)**: Ensure all routes are present:
  - `/runs`: `{ id: 'view-runs', label: 'Runs' }`
  - `/detail`: `{ id: 'view-detail', label: 'Détail' }`
  - `/projection`: `{ id: 'view-projection', label: 'Projection' }`
  - `/forge`: `{ id: 'view-forge', label: 'Forge' }`
  - `/admin`: `{ id: 'view-admin', label: 'Admin' }`
  - `/launch` (if present/added on upstream target branch): ensure route definition is preserved.
- **Helper Functions**: Ensure helper utilities like `resolveNamespaceId(win)` are preserved.
- **Mount / Initialisation Logic (`bootstrapCockpit` / `createRouter` `onMount` handler)**:
  - Ensure view initializations for `/forge` (mounting `mountForgeCockpit`) and `/launch` (if present) are both included in the `onMount` hook.
  - Retain `registerTeardown` handling and error safety.
- Stage resolved files: `git add factory/dashboard/js/app.mjs`
- Continue rebase: `git rebase --continue`

### 3. Verify Non-Router Files Intact
Verify that the following files are clean and unconflicted:
- `factory/dashboard/js/views/forge-cockpit.mjs`
- `factory/dashboard/js/components/forge-activity.mjs`
- `factory/dashboard/js/components/delivery-panel.mjs`
- `factory/tests/test-cockpit-forge.mjs`
- `specs/ae9fb67d_forge_cockpit_w2b.md`

### 4. Test Verification
Run all cockpit unit and integration tests using `node`:
- `node factory/tests/test-cockpit-shell.mjs`
- `node factory/tests/test-cockpit-forge.mjs`
- `node factory/tests/test-cockpit-run-detail.mjs`
- `node factory/tests/test-projection-governance.mjs`
- Run any launch route tests if available (e.g., `test-cockpit-run-launch.mjs` if added on upstream).

### 5. Finalize Branch
- Verify git status is clean and rebase is complete (`git status`).
- Check git commit log (`git log -n 5 --oneline`) to confirm clean linear history on top of `origin/integration/jalonD-cockpit-w2-a`.

## Verification Criteria
- `git status` shows clean working directory on `sbx/coday-forge-cockpit-7d56`.
- `factory/dashboard/js/app.mjs` exports `ROUTES` with `/runs`, `/detail`, `/projection`, `/forge`, `/admin`, and `/launch` (if present upstream), along with full mounting logic for both `/forge` and `/launch`.
- All cockpit node tests exit with code 0 and 0 failures.
