# Plan: Rebase `sbx/coday-forge-cockpit-7d56` onto `origin/integration/jalonD-cockpit-w2` and Resolve Router Conflicts

## Overview
The sandbox branch `sbx/coday-forge-cockpit-7d56` needs to be rebased onto `origin/integration/jalonD-cockpit-w2` (the remote integration branch for jalonD-cockpit-w2 that carries the `runs-launch` / `/launch` work). During this rebase a conflict arises in `factory/dashboard/js/app.mjs` and must be resolved carefully to preserve all route definitions and view initialization logic from both branches. All other cockpit files must remain unconflicted, and all dashboard tests must pass cleanly post-rebase.

## Pre-Rebase Checks
- Branch `sbx/coday-forge-cockpit-7d56` is currently checked out with a clean working tree.
- Remote tracking branch `origin/integration/jalonD-cockpit-w2` exists and is up to date.
- Tests currently pass:
  - `test-cockpit-shell.mjs`
  - `test-cockpit-forge.mjs`
  - `test-cockpit-run-detail.mjs`
  - `test-projection-governance.mjs`

## Execution Steps

### 1. Perform Git Rebase
- Run `git rebase origin/integration/jalonD-cockpit-w2`.

### 2. Resolve Conflicts in `factory/dashboard/js/app.mjs`
The conflict arises because upstream added `/launch` wiring while the branch added `/forge` wiring to the same router regions:
- **Imports**: keep both `mountRunLaunchView` (launch) and `mount as mountForgeCockpit` (forge).
- **Route Registrations (`ROUTES` object)**: ensure all routes are present:
  - `/runs`: `{ id: 'view-runs', label: 'Runs' }`
  - `/launch`: `{ id: 'view-launch', label: 'Lancer' }`
  - `/detail`: `{ id: 'view-detail', label: 'Détail' }`
  - `/projection`: `{ id: 'view-projection', label: 'Projection' }`
  - `/forge`: `{ id: 'view-forge', label: 'Forge' }`
  - `/admin`: `{ id: 'view-admin', label: 'Admin' }`
- **View registry**: keep the upstream `VIEW_MOUNTERS` registry (`/launch`).
- **Helper Functions**: ensure helper utilities like `resolveNamespaceId(win)` are preserved.
- **Mount / Initialisation Logic (`createRouter.mount`, `bootstrapCockpit` / `onMount`)**:
  - keep both the upstream `mountView(route)` call (drives `VIEW_MOUNTERS`, e.g. `/launch`);
  - and the forge `options.onMount` hook (mounts `mountForgeCockpit` for `/forge`);
  - `bootstrapCockpit` must pass `apiClient`, `mounters: VIEW_MOUNTERS` and the forge `onMount` handler together.
  - Retain `registerTeardown` handling and error safety.
- Stage resolved files: `git add factory/dashboard/js/app.mjs`
- Continue rebase: `git rebase --continue`

### 3. Verify Non-Router Files Intact
Verify that the following files are clean and unconflicted (byte-identical to their branch of origin):
- `factory/dashboard/js/views/forge-cockpit.mjs`
- `factory/dashboard/js/components/forge-activity.mjs`
- `factory/dashboard/js/components/delivery-panel.mjs`
- `factory/tests/test-cockpit-forge.mjs`
- `factory/dashboard/js/views/run-launch.mjs`
- `factory/tests/test-cockpit-run-launch.mjs`
- `specs/ae9fb67d_forge_cockpit_w2b.md`

### 4. Test Verification
Run all cockpit unit and integration tests using `node`:
- `node factory/tests/test-cockpit-shell.mjs`
- `node factory/tests/test-cockpit-forge.mjs`
- `node factory/tests/test-cockpit-run-detail.mjs`
- `node factory/tests/test-projection-governance.mjs`
- `node factory/tests/test-cockpit-run-launch.mjs`

### 5. Finalize Branch
- Verify git status is clean and rebase is complete (`git status`).
- Check git commit log (`git log -n 5 --oneline`) to confirm clean linear history on top of `origin/integration/jalonD-cockpit-w2`.

## Verification Criteria
- `git status` shows clean working directory on `sbx/coday-forge-cockpit-7d56`.
- `factory/dashboard/js/app.mjs` exports `ROUTES` with `/runs`, `/launch`, `/detail`, `/projection`, `/forge`, `/admin`, along with full mounting logic for both `/forge` and `/launch`.
- All cockpit node tests exit with code 0 and 0 failures.

## Resolution (as executed)
- Rebased onto `origin/integration/jalonD-cockpit-w2` @ `b6f0296b` (`merge runs-launch`). The only conflicted file was `factory/dashboard/js/app.mjs`.
- Kept **both** wiring mechanisms: `/launch` keeps the upstream `VIEW_MOUNTERS` + `mountView(route)` path; `/forge` keeps the branch's additive `options.onMount` hook. `createRouter.mount()` now invokes `mountView(route)` and then `options.onMount(route, …)`; `bootstrapCockpit()` passes `apiClient`, `mounters: VIEW_MOUNTERS` and the forge `onMount` handler together.
- All forge/launch component and view files were auto-merged cleanly and remain byte-identical to their source branch.
- Post-rebase tests: `test-cockpit-forge` (35), `test-cockpit-run-launch` (22), `test-cockpit-shell` (20), `test-cockpit-run-detail` (39), `test-projection-governance` (35) — all green.
