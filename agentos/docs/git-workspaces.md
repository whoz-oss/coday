# Optional namespace Git workspaces

## Product contract

Git is an optional namespace integration. With no `GIT_REPOSITORY` association, cases, file Exchanges and tool configuration retain their existing behavior. Associating a repository prepares an internal **bare repository** (Git objects and references, without a shared checkout) at `<mountRoot>/<namespaceId>/repository.git`, outside both Exchange roots. The Namespace Exchange holds shared documents only. The separate `autoWorktreeForRootCases` option defaults to false.

When enabled, each **new root case** gets a worktree in `repo/` under its Case Exchange root. All descendants share the entire Case Exchange, including documents outside Git. Existing families are never retroactively equipped, and disabling automation does not disconnect existing workspaces. Persisted bindings retain the settings used to create them.

**AgentOS does not create, name or rename working branches or pull requests.** A new worktree starts with a detached HEAD at the configured main branch's fetched commit. Agents create branches and PRs using their workflow tools. The case title has no effect on Git. Retrying preparation preserves any branch or local work already created by an agent.

The root case owns the durable workspace. Killing or replacing a contributor conversation does not delete it. Factory controllers and contributors can be sub-cases of this durable root.

To create a sub-case manually, use **Create sub-case** in a writable root case's actions (desktop, compact sidebar or mobile). The composer shows the selected parent; sending its first message creates the child with `parentCaseId`. The child appears under that parent and uses the family's existing worktree. This action also works in namespaces without Git.

## Provisioning and recovery

Case creation and the resource binding are recorded in a short database transaction. A background worker prepares the internal repository, fetches the configured remote main branch, freezes a per-case Git reference (`refs/agentos/base/<root-case-id>`), records the SHA in Neo4j, creates the detached worktree and runs the optional setup command there. No agent or file mutation is admitted before `READY`. Errors are visible and retryable; they never fall back to another directory.

Every new root case fetches its base from the remote, independently of existing local branches and worktrees. Existing cases keep their frozen base and local changes; updating them with merge/rebase remains an agent workflow responsibility. Fetch does not merge files, so there is no shared checkout to synchronize or resolve conflicts in. A failed fetch blocks provisioning instead of using a stale local base.

A crashed fetch is reconciled from the internal base reference. A setup marked as started but not completed requires explicit acknowledgement before replay, because installation scripts may have side effects. `FAILED` is not automatically retried. Saving the namespace Git settings explicitly requeues a failed internal clone.

For equipped cases, a durable Neo4j inbox stores expanded prompt commands, author and session context before accepting the request. `AddMessageRequest.requestId` deduplicates HTTP retries; the same id with different input is rejected. Commands run sequentially. Queued work resumes after readiness or restart. A command that had started when the process stopped enters `RECOVERY_REQUIRED`; it is never automatically replayed. The recovery action cancels its remaining instructions and permits new input on the same worktree. Answers bypass the command queue, and interrupt/kill cancel queued work. A saved answer awaiting execution survives restart; an already completed receipt reconciles a stranded runtime back to idle. Equipped scheduled prompts use a deterministic case identity per occurrence, so reclaim reuses the same case/worktree and accepted receipt.

Provisioning and lifecycle coordination target **one AgentOS instance per workstream**. They are not a distributed lease protocol. Ordinary concurrent edits by agents sharing a worktree remain a workflow responsibility.

## Tools and configuration

The built-in Exchange integration and REST API use `ExchangeRootResolver`. Effective BASH/TMUX/MCP_STDIO configuration uses the same resolved directory:

| Integration | Effective parameter |
| ----------- | ------------------- |
| BASH, TMUX  | `workingDirectory`  |
| MCP_STDIO   | `cwd`               |

These are per-run copies; saved integrations are not rewritten. `useCaseExchangeDirectory` defaults to true and can be disabled for an integration intentionally targeting another directory. A namespace without a Git workspace keeps the configured directory.

TMUX uses a distinct socket for each root workspace, shared by descendants. MCP connections are keyed by configuration, including the resolved working directory. Cleanup releases workspace TMUX/MCP resources and tracked Bash processes. Foreground Bash/setup commands wait for ordinary background shell jobs before finishing; long-lived development servers should use the scoped TMUX tools. Before deletion, an `lsof` scan also checks for processes holding a file or working directory inside the worktree, including detached jobs or jobs surviving an AgentOS restart. Missing or inconclusive process inspection blocks cleanup. These tools remain trusted shell execution, not an OS sandbox.

The namespace service account is used for managed clone/fetch and PR observation. Agents' workflow tools and forge integrations remain responsible for branch creation, push and PR creation, using their configured authentication. No branch or PR creation endpoint is provided by the workspace feature.

## Branch and PR observation

The observer reads `HEAD` from the managed worktree's administrative directory. Detached HEAD means `branchName = null` and **no Git icon beside the case title**. After an agent checks out a branch, its name and status are observed. Switching back to detached HEAD clears the old branch/PR projection.

The icon beside the root case title represents only a known associated PR: draft, open, merged or closed without merge. No PR icon appears for local branches, pushed branches without a PR, unavailable status or authentication errors. Its tooltip gives the PR number and state. The icon is independent of the runtime glyph and tree chevron, including root cases with children. Branch names, push state, modified files, unpushed commits, observation time and errors appear in the expanded workspace details.

When case files are opened during worktree preparation, the drawer shows a spinner and an explanation instead of a file-loading error. It checks pending workspace status every two seconds and loads the files automatically when ready. Failed preparation remains an error, and leaving the case cancels the wait. Namespaces and cases without Git keep their normal file loading behavior.

The current hosting adapter observes GitHub.com PRs using the namespace service account and the [GitHub pull request API](https://docs.github.com/en/rest/pulls/pulls#list-pull-requests). Other Git hosts can still supply repositories; their PR state stays unknown until a hosting adapter is provided. API failures or incomplete results never mean that a PR is closed.

A PR checked out under a local alias (for example `pr-1301`) is also recognized through GitHub's [commit-associated PRs](https://docs.github.com/en/rest/commits/commits#list-pull-requests-associated-with-a-commit), when exactly one PR targeting the configured repository has that exact head commit. PRs that merely contain the commit are excluded. This fallback accepts fork PRs, but does not associate the initial workspace base, the main branch or a detached HEAD. Ambiguous matches remain unknown. Without a matching remote branch name, the association requires an exact PR head: local commits or an outdated checkout can therefore leave it unresolved, and GitHub's commit endpoint may omit closed, unmerged PRs.

## Case deletion and worktree cleanup

AgentOS keeps its existing deletion behavior: stop the case's in-memory execution, mark it `removed` in Neo4j and hide it from normal listings. Events remain stored. Deleting a root does not delete its descendants. There is no separate finalisation or archive workflow, and closing or merging a PR has no effect on cases or worktrees.

The workspace worker observes this existing deletion marker and removes the local worktree only after every case in its family has been deleted and executions/tools have stopped. Surviving sub-cases continue using the same shared Exchange, even after deletion of the root case. Deleting a case without Git remains unchanged.

Cleanup uses `git worktree remove` without force. Git refusal (for example, local uncommitted files or a locked worktree) leaves the worktree on disk and records the reason; it never prevents case deletion. A later worker pass retries cleanup. Only `repo/` is removed: documents beside it, messages in Neo4j, and local/remote branches are retained. No PR lookup or merge hook is involved.

## HTTP endpoints

All existing Case/Namespace permission checks apply.

- `GET /api/cases/{caseId}/workspace`
- `GET /api/namespaces/{namespaceId}/workspaces`
- `POST /api/cases/{rootCaseId}/workspace/{refresh|retry}`
- `POST /api/cases/{caseId}/workspace/recover`

`retry` belongs to the root case and accepts `acknowledgeSetupReplay` (default false). Refresh and execution recovery are available from each case. Deletion continues to use the existing Case DELETE endpoint.

## Deployment

Build the service Dockerfile with `agentos/` as its context. The image includes JDK-compatible Java 25, Git, Bash, TMUX, lsof, Node 22, Corepack, Python and common native build tools, plus the bundled plugins. Repository-specific toolchain versions still belong in the repository/setup configuration.

Supply the existing `AGENTOS_ENCRYPTION_KEY` and `AGENTOS_ENCRYPTION_SALT` settings at runtime. The image serves port 8124 by default and checks `/management/health`; Compose overrides the port to 8080.

Mount persistent storage for `/app/data` (Neo4j and Exchange), and keep the Exchange mount path stable. Git worktrees record absolute paths; moving the volume to another container path requires explicit repair. `AGENTOS_EXCHANGE_MOUNT_ROOT` selects the Exchange root. The default image uses `/app/data/exchange`.

The managed Git runner clears sensitive service environment variables, disables hooks and credential helpers, restricts transports and pins Git metadata paths. Exchange APIs deny `.git` access, including symlink aliases. Shell-capable agents remain trusted at the service OS-user level and share a namespace's Git object store; existing Case permissions are not a filesystem sandbox.

Repository URL replacement or storage relocation remains an explicit operator action: configuration changes must not silently move a shared clone or destroy local files. The creation automation switch can be changed independently.

## Exchange environment panel and diff

The Case Exchange section shows worktree location and branch, aggregate added/deleted lines, and a read-only file-by-file diff dialog implemented with diff2html. The diff is the net change from the merge-base of HEAD and the locally known origin/main branch to the working directory: committed, staged, unstaged and non-ignored untracked files are included. It does not fetch the remote on each view. Binary/link previews and oversized patches show an explanation. Renames are represented as delete/add pairs. External diff drivers and text conversion are disabled.

Inside `repo/`, the file tree uses the same branch-plus-local diff as the Changes counter. Files show `A` (added), `M` (modified), `D` (deleted), `U` (untracked), `T` (type changed), or `!` (merge conflict). Folder markers count changed descendants. Clicking a status opens that file's diff; ordinary filenames still open the content preview. Deleted files remain visible as struck-through rows without download/delete actions, including paths whose parent directories no longer exist. Shared documents and files outside `repo/` have no Git decoration. The tree and panel reuse one observation request, and background updates preserve the current directory and DOM rows.

The Case panel lists distinct participating agent IDs from running/finished events across the root and all descendants; selected-but-never-run agents do not count. Unreadable cases are excluded before querying events. Namespace views contain documents only, without a Git panel or case activity. The read-only endpoints use the same Case READ checks as file browsing:

- `GET /api/cases/{id}/exchange/environment`
- `GET /api/cases/{id}/exchange/diff?path=<repository-relative-file>`

Namespace files are ordinary shared documents. Repository configuration and preparation status are available in namespace Git settings.

Uploaded documents land at the outer Exchange root, outside Git. File tools address source files with `repo/…`; BASH, TMUX and opted-in MCP processes start inside `repo/`. Cleanup only removes `repo/`, leaving outer documents in place.

### Existing local data

No automatic migration is provided for older namespace checkouts. Provisioning refuses an existing checkout at `shared/.git` or `shared/repo/.git` instead of silently creating a second repository and orphaning its worktrees. Existing installations require an operator to stop the service, back up the data, relocate the common Git directory, and repair linked-worktree pointers before restarting. Git references, indexes and working files must be preserved.
