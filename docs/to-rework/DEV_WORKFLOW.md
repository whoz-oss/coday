# Development Workflow

This document outlines the technical workflow steps and rules for contributing to the Coday project.

## Branch Naming

Always follow these branch naming conventions:

```
fix/username/issue-XXXX-short-description    # For bug fixes
feature/username/issue-XXXX-feature-name     # For new features
refactor/username/issue-XXXX-description     # For code refactoring
docs/username/issue-XXXX-description         # For documentation updates
chore/username/issue-XXXX-description        # For maintenance tasks
build/username/issue-XXXX-description        # For build system changes
```

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

1. Create branch using the naming convention, from the remote `master` branch
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
5. Create PR with conventional commit format as title (e.g., "fix: #XXXX short description")

## Feature Development Workflow

1. Create branch using the naming convention, from the remote `master` branch
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
7. Create PR with conventional commit format as title (e.g., "feat: #XXXX short description")

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

## Pull Request Requirements

1. PR title must follow conventional commit format
2. All commits in a PR must be squashed when merging (squash and merge is mandatory)
3. Address all review comments before merging
4. Wait for CI checks to pass before merging

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
