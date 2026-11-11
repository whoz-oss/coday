# Development Workflow

This document outlines the technical workflow steps and rules for contributing to the Coday project.

## Choosing the Base Branch (do this first)

Before creating any branch, determine whether the work belongs to a **workstream**. Getting this wrong
is costly: a branch cut from `master` instead of the workstream misses the workstream's code, targets the
wrong PR base, and skips the integration build reviewers rely on.

1. Look at the current branch (`git branch --show-current`).
2. If it is `work/<slug>/...` or `workstream/<slug>/...`, the work belongs to the `<slug>` workstream,
   unless the user explicitly says otherwise.
3. Otherwise, check whether the user or the issue names a workstream, and list the existing ones with
   `git branch -r --list 'origin/workstream/*'`.
4. If there is any doubt, ask before creating the branch.

| Situation | Branch from | Branch name | PR target |
|---|---|---|---|
| Work inside workstream `<slug>` | `origin/workstream/<slug>/<base>` | `work/<slug>/issue-XXXX-description` | `workstream/<slug>/<base>` |
| Standalone work | `origin/master` | `<type>/username/issue-XXXX-description` | `master` |

In a workstream, always use the `work/` prefix, never `feature/`, `fix/`, etc. Only `work/<slug>/*` PRs
are promoted to `integration/<slug>` and get prerelease artifacts. The conventional type (`feat`, `fix`,
...) goes in the commit message and the PR title, not in the branch name.

Always branch from the **remote** workstream or `master` (`git fetch` first), never from your current
local branch: another work branch such as `work/<slug>/medley` may contain unmerged changes.

## Branch Naming

Always follow these branch naming conventions:

```
# Standalone work (base and PR target: master)
fix/username/issue-XXXX-short-description    # For bug fixes
feature/username/issue-XXXX-feature-name     # For new features
refactor/username/issue-XXXX-description     # For code refactoring
docs/username/issue-XXXX-description         # For documentation updates
chore/username/issue-XXXX-description        # For maintenance tasks
build/username/issue-XXXX-description        # For build system changes

# Workstream work (base and PR target: workstream/<slug>/<base>)
work/<slug>/issue-XXXX-description           # Any change type inside the <slug> workstream
```

To find the workstream branch of a `work/<slug>/...` branch, keep the slug and look for
`workstream/<slug>/*` (usually `workstream/<slug>/master`). The workstream branch has a `<base>` suffix,
the work branch does not. See [WORKSTREAM_WORKFLOW.md](../WORKSTREAM_WORKFLOW.md).

## Commit Messages

Use conventional commit format:

```
fix: #XXXX short description       # For bug fixes
feat: #XXXX short description      # For features
refactor: #XXXX short description  # For refactoring
docs: #XXXX short description      # For documentation
test: #XXXX short description      # For test additions/changes
chore: #XXXX short description     # For maintenance tasks
build: #XXXX short description     # For build system changes
```

## Bug Fix Workflow

1. Choose the base branch (see [Choosing the Base Branch](#choosing-the-base-branch-do-this-first)), then
   create the branch from that remote base using the naming convention
2. Implement the fix
3. Test the fix:

   ```bash
   # Compile the entire project
   pnpm nx run-many -t build

   # Lint and test affected
   pnpm nx affected -t lint test

   # Verify fix works in both web and terminal interfaces
   pnpm start    # Test terminal interface
   pnpm web      # Test web interface
   ```

4. Commit and push
5. Create PR with conventional commit format as title (e.g., "fix: #XXXX short description"), targeting
   the workstream branch for `work/<slug>/*` branches, `master` otherwise

## Feature Development Workflow

1. Choose the base branch (see [Choosing the Base Branch](#choosing-the-base-branch-do-this-first)), then
   create the branch from that remote base using the naming convention
2. Consider architectural impact before implementation
3. Follow SOLID principles and existing patterns. KISS: Keep It Stupid Simple
4. Implement the feature
5. Verify:

   ```bash
   # Compile the entire project
   pnpm nx run-many -t build

   # Lint and test affected
   pnpm nx affected -t lint test

   # Add automated tests if appropriate, then verify
   pnpm nx run-many -t lint test -p <project>
   ```

6. Commit and push
7. Create PR with conventional commit format as title (e.g., "feat: #XXXX short description"), targeting
   the workstream branch for `work/<slug>/*` branches, `master` otherwise

## Running the packaged stack locally (and testing the first-run experience)

End users always run `npx @whoz-oss/coday-web`, and the browser always opens on `/welcome`: the first-run wizard while
the setup is not done, otherwise a plain loading page that leads into the app once AgentOS is up. `web:local` is the same single command for the monorepo: it builds your local
code and runs it like an npx user would, without publishing and without touching your own `~/.coday` or `~/Coday`:

```bash
# Everyday: builds client, factory-cockpit, server, web + the AgentOS/Factory boot JARs, packs the 4 npm packages,
# re-installs them and runs the installed `coday-web` on the persistent root ~/.coday-local (override: CODAY_LOCAL_DIR).
# First time: the wizard. Afterwards: the loading page, then the app; your setup and data are kept.
pnpm nx run web:local
pnpm nx run web:local -- --managed         # one process, plain console (what the desktop apps get)
pnpm nx run web:local -- --no-open         # do not open the browser (it opens on each start in the Nx TUI)

# Test the first-run wizard again: deletes ~/.coday-local/config and files first (printed), then starts
pnpm nx run web:local -- --fresh           # `pnpm nx run web:first-run` is an alias of this

# Automated: Playwright drives the wizard against a real AgentOS with dummy keys (no LLM call).
# Skips itself, with a reason, when Java 25 or the built JARs are missing.
pnpm nx run client-e2e:first-run-e2e
```

The script prints every path it uses (root, install dir, configDir, logs) and whether the run is a first run or a resume.
Logs of a run are in
`<configDir>/logs/{server,agentos,factory}.log`. Java 25 is required for the JVM services; to see the "Java missing" step
of the wizard, run with `--fresh` and a `JAVA_HOME` that points to an older JDK. Details: `apps/web/README.md`.

For day-to-day development use `pnpm web:serve:dev` (continuous Nx tasks: client, factory-cockpit, server, AgentOS and
Factory, one pane each; the wizard is not shown there because the services are external).

## Worktree Workflow

Worktrees allow parallel work on multiple branches without switching the main project directory. Each worktree is registered as a Coday sub-project, enabling agents to work in isolation per issue.

**Naming**: worktree directories and their derived project names follow the branch name sanitized with `-` replacing `/`.
Example: branch `feature/username/issue-0585-worktree-workflow` → project `coday__feature-username-issue-0585-worktree-workflow`.

**Lifecycle**:
1. PM creates the worktree via the `GIT_WORKTREE` integration when initiating work on an issue.
2. Implementation agents (Sway, Dev…) work within the worktree's Coday sub-project.
3. PM removes the worktree once the linked PR is merged or the issue is closed.

**Commands** (run from the main project root):
```bash
git worktree list                              # list active worktrees
git worktree add ../coday__feat-xxx feat/xxx   # create (handled by PM via tools)
git worktree remove ../coday__feat-xxx         # remove (handled by PM via tools)
git worktree prune                             # clean up stale references
```

## Workstream Workflow

For multi-issue initiatives that need to integrate and be reviewed together before reaching
`master`, Coday provides a parallel delivery lane based on three branch types:
`workstream/<slug>/<base>`, `work/<slug>/issue-XXXX-desc`, and `integration/<slug>`.

Each work branch PR that passes CI is automatically promoted to the integration branch, which
publishes a coherent prerelease artifact set (npm packages + AgentOS JARs) that reviewers and
external systems can consume before the merge happens.

See [WORKSTREAM_WORKFLOW.md](../WORKSTREAM_WORKFLOW.md) for the full architecture.

## Pull Request Requirements

1. PR title must follow conventional commit format
2. PR base must match the branch: `work/<slug>/*` → `workstream/<slug>/<base>`, everything else → `master`
3. All commits in a PR must be squashed when merging (squash and merge is mandatory)
   - **Exception**: `workstream/* → master` PRs MUST use a merge commit, not a squash.
     Squashing would collapse a multi-issue initiative into a single changelog entry and a
     single version bump. See [WORKSTREAM_WORKFLOW.md](../WORKSTREAM_WORKFLOW.md#merge-policy).
4. Address all review comments before merging
5. Wait for CI checks to pass before merging

## Important Commands

```bash
# Start the terminal interface
pnpm start

# Start the web interface
pnpm web

# Lint and test (recommended)
# Nx infers test and lint targets automatically — no explicit targets needed in project.json.
# Run both lint and test together (recommended for pre-commit / PR validation):
pnpm nx run-many -t lint test -p <project>   # single project
pnpm nx affected -t lint test                # affected projects only (ideal for PRs)
pnpm nx run-many -t lint test                # all projects

# Run individually if needed
pnpm nx test <project>                       # test only, single project
pnpm nx lint <project>                       # lint only, single project
pnpm nx test <project> --configuration=ci   # test with coverage (CI profile)

# Debug web interface
pnpm server:debug
```
