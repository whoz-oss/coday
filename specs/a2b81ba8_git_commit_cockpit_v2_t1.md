# Implementation Plan: Git Commit for Cockpit V2 T1 Foundation

This plan strictly covers executing the specified git actions for the already prepared T1 codebase state, with zero code modifications, builds, or tests.

## User Request Summary
1. `git add -A` at repo root.
2. Commit with message:
   `feat(cockpit-v2): fondations app Nx + socle partagé (theme M3, shell, core/models/store, atomes sf-*, pipes) + service /cockpit-v2 (factory-service Kotlin) + placeholders ; retrait du cablage apps/server`
3. Output `git log -1 --stat` and the commit SHA.
4. No file recreations/modifications, no git push.

## Proposed Steps

### Step 1: Stage All Changes
Run:
`git add -A`

### Step 2: Create Git Commit
Run:
`git commit -m "feat(cockpit-v2): fondations app Nx + socle partagé (theme M3, shell, core/models/store, atomes sf-*, pipes) + service /cockpit-v2 (factory-service Kotlin) + placeholders ; retrait du cablage apps/server"`

### Step 3: Inspect Commit Log & Output SHA
Run:
`git log -1 --stat`

Get commit SHA using:
`git rev-parse HEAD`

## Verification Plan
- Verify that `git status` is clean after commit.
- Confirm commit message and stat output match expected changes.
