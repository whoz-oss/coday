# Git Workflow

This document defines the git conventions and expected behaviors for this project.
Gitay uses it to translate high-level intentions into the correct sequence of git commands.

## Branch Naming

Branches must follow this pattern:

```
fix/username/issue-XXXX-short-description       # Bug fixes
feature/username/issue-XXXX-feature-name        # New features
refactor/username/issue-XXXX-description        # Code refactoring
docs/username/issue-XXXX-description            # Documentation
chore/username/issue-XXXX-description           # Maintenance tasks
build/username/issue-XXXX-description           # Build system changes
```

The `username` segment comes from the git user config (`git config user.name` or `user.email`).
When creating a branch for an issue, read the issue number and a short slug from the request.
Always branch off the remote `master` (fetch first, then branch from `origin/master`).

## Commit Messages

Use the conventional commit format:

```
fix: #XXXX short description
feat: #XXXX short description
refactor: #XXXX short description
docs: #XXXX short description
test: #XXXX short description
chore: #XXXX short description
build: #XXXX short description
```

The issue number is optional when there is no associated issue.

## Typical Workflows

### Prepare a working branch
1. `git fetch origin` — get latest remote state
2. `git checkout -b <branch-name> origin/master` — create branch from remote master
3. Report the branch name created

### Commit specific files
1. `git status` — verify the current state
2. `git add <files>` — stage only the requested files
3. `git commit -m "<conventional message>"` — commit with the appropriate type and message
4. Report the commit hash and summary

### Commit all tracked changes
1. `git status` — verify what is staged/unstaged
2. `git add -u` — stage all modifications to tracked files (does NOT add untracked files)
3. `git commit -m "<conventional message>"`
4. Report the commit hash and summary

### Push a branch
1. `git push -u origin <branch>` for first push, `git push` for subsequent ones
2. Report the remote URL or confirmation

### Revert changes
- **Specific files, not committed**: `git checkout -- <files>` (or `git restore <files>`)
- **All unstaged changes**: `git restore .` — only on tracked files
- **Last commit (keep changes staged)**: `git reset --soft HEAD~1`
- **Last commit (discard changes)**: `git reset --hard HEAD~1` — destructive, confirm before running
- **Published commit**: prefer `git revert <hash>` to preserve history

## Handling Unexpected State

Before acting on a request, always run `git status` to understand the current state.
Report any unexpected findings to the caller before proceeding.

### Untracked files
Untracked files are never staged or deleted unless explicitly requested.
If a commit request would logically include an untracked file, flag it and ask whether to include it.

### Uncommitted changes on wrong files
If there are unstaged or staged changes on files not mentioned in the request,
do NOT reset or stash them silently. Report their presence and ask for instructions.

### Dirty working tree when creating a branch
If the working tree has uncommitted changes when asked to create a branch,
report the situation. Do not stash automatically — ask whether to stash, commit, or ignore.

### Merge conflicts
Do not attempt to resolve merge conflicts. Report the conflicting files and stop.

### Detached HEAD
If the repo is in detached HEAD state, report it before doing anything else.
