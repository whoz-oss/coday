# Hotfix Branches

Hotfix branches allow targeted fixes to be released on an older version line without impacting ongoing development on `master`.

## How it works

Hotfix branches follow the pattern `hotfix/<major>.<minor>` (e.g. `hotfix/0.183`). The CI pipeline detects them automatically:

- PRs targeting a `hotfix/*` branch go through the full lint/test/JVM gate, same as PRs targeting `master`
- Pushing to a `hotfix/*` branch triggers the release pipeline

Version computation is handled by Nx out of the box. On a hotfix branch, git only sees tags reachable from that branch — so Nx naturally picks up `release/0.183.x` as the base and ignores master's newer tags entirely.

## Prerequisites

A hotfix branch for `0.183.x` is only safe to cut once master has moved to `0.184.0` or higher. If master is still releasing `0.183.x`, there is no need for a hotfix branch — just fix on master normally.

If master hasn't moved past `0.183` yet, advance it first using the manual workflow trigger (see below).

## Advancing master before cutting a hotfix branch

Go to **GitHub → Actions → Release and Publish → Run workflow**, select `minor` from the specifier dropdown, and run it. This bypasses conventional commits and forces a minor bump — master moves from `0.183.x` to `0.184.0` immediately.

Wait for the release workflow to complete before cutting the hotfix branch.

## Creating a hotfix branch

From the release tag you want to patch (e.g. the last `0.183.x` tag):

```bash
git checkout -b hotfix/0.183 release/0.183.4
git push origin hotfix/0.183
```

## Applying fixes

Fix on `master` first, then cherry-pick to the hotfix branch. Since `hotfix/*` branches are protected, changes must go through a PR:

```bash
# Create a local branch off the hotfix branch
git checkout -b fix/my-fix hotfix/0.183
git cherry-pick <commit-sha>
git push origin fix/my-fix
# Open a PR targeting hotfix/0.183
```

Once the PR is merged, the release pipeline triggers automatically. Nx finds `release/0.183.4` as the base, detects the `fix:` commit, and produces `0.183.5`. Subsequent fixes produce `0.183.6`, and so on.

> Always fix on `master` first. This ensures the fix is captured in the primary codebase and prevents it from being lost when the hotfix branch is eventually deleted.

## Deleting a hotfix branch

When a newer minor line supersedes the hotfix line (e.g. `0.184.x` covers all the same fixes), delete the branch:

```bash
git push origin --delete hotfix/0.183
```

Release tags (`release/0.183.x`) and GitHub Releases are preserved permanently.
