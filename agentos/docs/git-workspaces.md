# Optional namespace Git workspaces

## Product contract

Git is an optional namespace integration. With no `GIT_REPOSITORY` association, cases, file Exchanges and tool configuration retain their existing behavior. Associating a repository prepares an internal **bare repository** (Git objects and references, without a shared checkout) at `<mountRoot>/<namespaceId>/repository.git`, outside both Exchange roots. The Namespace Exchange holds shared documents only. The separate `autoWorktreeForRootCases` option defaults to false.

Git is available only when the `agentos-git-plugin` is loaded. The plugin registers the `GIT` integration type, which gives agents their Git tools; its presence also enables the namespace association. Without it the **Git** entry is hidden, the namespace Git endpoints answer 404, the generic integration configuration API refuses `GIT_REPOSITORY`, and no new root case is equipped. Families equipped earlier keep their worktree, tools and cleanup. Associating a repository never requires defining a `GIT` integration.

When enabled, each **new root case** gets a worktree in `repo/` under its Case Exchange root. All descendants share the entire Case Exchange, including documents outside Git. Existing families are never retroactively equipped, and disabling automation does not disconnect existing workspaces. Persisted bindings retain the settings used to create them.

**AgentOS does not create, name or rename working branches or pull requests.** A new worktree starts with a detached HEAD at the configured main branch's fetched commit. Agents create branches and PRs using their workflow tools, such as the tools of a `GIT` integration. The case title has no effect on Git. Retrying preparation preserves any branch or local work already created by an agent.

The root case owns the durable workspace. Killing or replacing a contributor conversation does not delete it. Factory controllers and contributors can be sub-cases of this durable root.

To create a sub-case manually, use **Create sub-case** in a writable root case's actions (desktop, compact sidebar or mobile). The composer shows the selected parent; sending its first message creates the child with `parentCaseId`. The child appears under that parent and uses the family's existing worktree. This action also works in namespaces without Git.

## Provisioning and recovery

Case creation and the resource binding are recorded in a short database transaction. A background worker prepares the internal repository, fetches the configured remote main branch, freezes a per-case Git reference (`refs/agentos/base/<root-case-id>`), records the SHA in Neo4j, creates the detached worktree and runs the optional setup command there. No agent or file mutation is admitted before `READY`. Errors are visible and retryable; they never fall back to another directory. Public failure reasons identify the failed operation without exposing subprocess output, exception messages or credentials. The Git settings form remains read-only until its full saved configuration has loaded successfully.

Every new root case fetches its base from the remote, independently of existing local branches and worktrees. Existing cases keep their frozen base and local changes; updating them with merge/rebase remains an agent workflow responsibility. Fetch does not merge files, so there is no shared checkout to synchronize or resolve conflicts in. A failed fetch blocks provisioning instead of using a stale local base.

A crashed fetch is reconciled from the internal base reference. A checkout interrupted before its workspace was ever ready (typically a redeploy during `worktree add`, before setup started) is moved as is into internal workspace support storage and recreated: nothing is deleted, and no agent or setup ran in it yet. An incomplete checkout of a workspace that was already ready remains failed until its index and checkout are repaired explicitly; retry never removes its files. A setup marked as started but not completed requires explicit acknowledgement before replay, because installation scripts may have side effects. Setup keeps HOME and package-manager caches in internal workspace support storage, outside both browsable Exchanges and `repo/`. This state is removed with the deleted family's workspace; setup-created symlinks never cause deletion of their targets. `FAILED` is not automatically retried. Saving the namespace Git settings explicitly requeues a failed internal clone. The dedicated Git settings endpoint and generic integration configuration CRUD share this validation and preparation policy.

Agent execution uses the existing AgentOS runtime and in-memory command queue. Messages are stored through the ordinary conversation event path. Runs wait while their worktree is being prepared, then start when it is ready. When the launch check itself fails (for example a transient database error), the turn is retried after each delay of `agentos.case.admission-retry-delays-ms` (1 s, 5 s, 30 s by default), then dropped with a warning and the case returns to IDLE; an error never starts the run. Server restarts do not replay deferred or interrupted instructions. An orderly server shutdown leaves equipped non-terminal cases IDLE so a user can send a fresh instruction after restart; explicit KILLED/ERROR states remain terminal. A concurrent Kill also cancels an instruction admitted before its execution starts. The worktree is retained. Equipped cases in KILLED/ERROR reject new messages before runtime hydration; this workspace guard does not apply to ordinary cases. Without a workspace, the existing API behavior accepts a new message and can resume a terminal case, although the existing UI still treats KILLED/ERROR as terminal. There is no additional command journal, HTTP request deduplication, or execution recovery action. Scheduled prompts use the existing scheduler behavior.

Provisioning and lifecycle coordination target **one AgentOS instance per workstream**. They are not a distributed lease protocol. Ordinary concurrent edits by agents sharing a worktree remain a workflow responsibility.

## Tools and configuration

The built-in Exchange integration and REST API use the Exchange-owned `ExchangeRootResolver` contract. Its result exposes file ownership, directory, working directory and availability without a Git binding. `GitExchangeRootResolver` implements that contract and keeps binding resolution and mutation locks inside the Git domain. The case runtime likewise delegates admission coordination to `CaseLaunchGate`; it does not acquire Git locks directly.

Effective BASH/TMUX/MCP_STDIO configuration uses the same resolved working directory:

| Integration | Effective parameter |
| ----------- | ------------------- |
| BASH, TMUX  | `workingDirectory`  |
| MCP_STDIO   | `cwd`               |
| GIT         | `workingDirectory`, plus `gitDir`, `commonGitDir`, `repositoryUrl` and `mainBranch` |

These are per-run copies; saved integrations are not rewritten. For BASH and TMUX, `useCaseExchangeDirectory` defaults to true and can be disabled for an integration intentionally targeting another directory; their shells also use the family's workspace support directory as `HOME` (with `XDG_CACHE_HOME` below it), the same one setup used, so package-manager stores and daemons match what setup installed and are never shared between families. MCP_STDIO enters the workspace only when `useCaseExchangeDirectory` is explicitly true: agents do not drive such a server, it often holds credentials in its environment, and project files in the agent-writable worktree (such as `.npmrc`) could run code inside it. A namespace without a Git workspace keeps the configured directory.

A `GIT` integration only exists inside a Git workspace. It always targets the family's worktree, with the administrative directory pinned from the binding rather than read from the worktree's `.git` file, and the repository URL and main branch recorded when the family was equipped. Saved values never override this context. Outside a Git workspace, or for a user without write access to it, agents receive no Git tool.

TMUX uses a distinct socket for each root workspace, shared by descendants. MCP connections are keyed by configuration, including the resolved working directory. Cleanup releases workspace TMUX/MCP resources and tracked Bash processes. Bash commands preserve normal background-job semantics: redirecting a background job’s output allows the shell command to return. Output collection does not occupy the JVM common pool or wait indefinitely for a background child to close its pipes. Tracked descendants can still be stopped during workspace cleanup: they receive SIGTERM and a grace period before being killed; scoped TMUX tools are available for persistent development sessions. Before deletion, an `lsof` scan also checks for processes holding a file or working directory inside the worktree, including detached jobs or jobs surviving an AgentOS restart. Missing or inconclusive process inspection blocks cleanup. On Linux, only a verified zombie (which cannot hold files or execute code) is exempted from the live-process check. These tools remain trusted shell execution, not an OS sandbox.

The namespace service account is used for managed clone/fetch and PR observation. Agents' workflow tools and forge integrations remain responsible for branch creation, push and PR creation, using their configured authentication. No branch or PR creation endpoint is provided by the workspace feature.

### Git tools for agents

A `GIT` integration, associated with an agent like any other integration, gives it these tools in its family's worktree:

| Tool | Effect |
| ---- | ------ |
| `git_status` | Branch or detached HEAD, current commit, whether the branch was pushed, changed files |
| `git_create_branch` | Creates a branch at the current commit and checks it out |
| `git_commit` | Stages the given paths, or every change, and commits on the current branch |
| `git_fetch` | Updates `origin/<branch>` (the main branch by default) without touching local work |
| `git_push` | Pushes the current branch to the branch of the same name, optionally with a lease after a rebase |
| `git_create_pull_request` | Opens a GitHub pull request from the pushed current branch |

There is no worktree tool and no free Git command: the service alone creates and removes worktrees. Push, fetch and pull requests use the credentials of the user running the case, from the auth setting bound to the integration; there is no fallback to the namespace service account. Bind a setting in which each user holds their own token (OAuth, or a personal token per user): a shared static secret would make every user act with that same token. Commits are authored as that user, with the GitHub no-reply address of the token's account on GitHub, and the identity-provider email elsewhere. The main branch is never pushed, a commit requires a branch, and a pull request requires its branch to be pushed first.

These tools run through the same hardened runner as the service: hooks, credential helpers and signing programs from the shared configuration never run, commands that may apply a filter first refuse executable filters, and push and fetch run in a private network context with an explicit URL, so an agent-written `pushurl`, `insteadOf` or credential helper never sees the user's token. The PR badge reflects a new push or pull request at the next observation pass.

## Branch and PR observation

The observer reads `HEAD` from the managed worktree's administrative directory. Detached HEAD means `branchName = null` and **no Git icon beside the case title**. After an agent checks out a branch, its name and status are observed. Switching back to detached HEAD clears the old branch/PR projection. The remote branch is fetched into a private `refs/agentos/observed/<root case id>` ref: observation never updates the agents' `refs/remotes/origin/*`, which `push --force-with-lease` uses as its expected value. The dirty-state check does not enter submodules, so their own filters never run in the service.

The icon beside the root case title represents only a known associated PR: draft, open, merged or closed without merge. No PR icon appears for local branches, pushed branches without a PR, unavailable status or authentication errors. Its tooltip gives the PR number and state. The icon is independent of the runtime glyph and tree chevron, including root cases with children. Branch names, PR details and changed files appear in the Files environment panel. The conversation banner is reserved for preparation, failure and cleanup notices; preparation retry remains available when Files is closed.

When case files are opened during worktree preparation, the drawer shows a spinner and an explanation instead of a file-loading error. It shares workspace status with the conversation banner and namespace list, and loads the files automatically when ready. Failed preparation remains an error, and leaving the case cancels the wait. Namespaces and cases without Git keep their normal file loading behavior.

The current hosting adapter observes GitHub.com PRs using the namespace service account and the [GitHub pull request API](https://docs.github.com/en/rest/pulls/pulls#list-pull-requests). Other Git hosts can still supply repositories; their PR state stays unknown until a hosting adapter is provided. API failures or incomplete results never mean that a PR is closed.

A PR checked out under a local alias (for example `pr-1301`) is also recognized through GitHub's [commit-associated PRs](https://docs.github.com/en/rest/commits/commits#list-pull-requests-associated-with-a-commit), when exactly one PR targeting the configured repository has that exact head commit. PRs that merely contain the commit are excluded. This fallback accepts fork PRs, but does not associate the initial workspace base, the main branch or a detached HEAD. Ambiguous matches remain unknown. Without a matching remote branch name, the association requires an exact PR head: local commits or an outdated checkout can therefore leave it unresolved, and GitHub's commit endpoint may omit closed, unmerged PRs.

## Case deletion and worktree cleanup

AgentOS keeps its existing deletion behavior: stop the case's in-memory execution, mark it `removed` in Neo4j and hide it from normal listings. Events remain stored. Deleting a root does not delete its descendants. There is no separate finalisation or archive workflow, and closing or merging a PR has no effect on cases or worktrees.

The workspace worker observes this existing deletion marker and removes the local worktree only after every case in its family has been deleted and executions/tools have stopped. Surviving sub-cases continue using the same shared Exchange, even after deletion of the root case. Deleting a case without Git remains unchanged. A worktree that holds another linked worktree (for example one an agent created in an ignored directory) is kept until that worktree is removed. A removal interrupted midway, for example by a redeploy, is completed on a later pass when the service's own removal marker is present and the commit retained before removal is still the worktree's HEAD.

Cleanup explicitly checks non-ignored untracked files even when `status.showUntrackedFiles` hides them (Git-ignored files such as dependency caches remain removable), then retains HEAD under an internal `refs/agentos/retained/<root-case-id>` reference before running `git worktree remove` without force. This keeps detached commits reachable without creating a user branch. Worktree creation and removal use the existing clone timeout so large checkouts are not limited to the short plumbing timeout. Status inspection does not enter submodules, so their own filters never run in the service; Git itself refuses to remove a worktree with initialized submodules. Git refusal (for example, local uncommitted files, a locked worktree or a nested linked worktree) leaves the worktree on disk and records the reason; it never prevents case deletion. A later worker pass retries cleanup. Creation and cleanup never run a global worktree prune: another family may have a temporarily unavailable directory and still own staged changes. If the deleted family’s directory is already missing, cleanup targets only its verified registration and preserves it when it contains a changed index, a lock or submodule metadata. Retrying an active family with a missing registered worktree requires restoring or explicitly repairing that checkout first. The worktree and its internal setup files are removed: documents beside `repo/`, messages in Neo4j, retained commits and local/remote branches remain. No PR lookup or merge hook is involved.

## HTTP endpoints

All existing Case/Namespace permission checks apply. Shared family files additionally require the same permission on their owning root case, including when that root has been soft-deleted. File capabilities, diffs and workspace tools follow this rule; access to a contributor conversation alone does not grant access to the shared files.

- `GET /api/cases/{caseId}/workspace`
- `GET /api/namespaces/{namespaceId}/workspaces`
- `POST /api/cases/{rootCaseId}/workspace/{refresh|retry}`

`retry` belongs to the root case and accepts `acknowledgeSetupReplay` (default false). Refresh is available from each case. Deletion continues to use the existing Case DELETE endpoint.

## Deployment

Managed workspaces require Git and CA certificates; cleanup also requires `lsof`, and repositories using Git LFS require `git-lfs`. The service Dockerfile adds these runtime dependencies and a writable data directory. Repository-specific toolchains and plugin deployment follow the existing deployment configuration.

Deploy the `agentos-git-plugin` JAR with the other plugins (`./gradlew deployPlugins` locally) to enable Git on an instance. Server-side Git runs through the `agentos-git` library, shared by the service and the plugin; the service binds the `agentos.git` settings.

Mount persistent storage for `/app/data` (Neo4j and Exchange), and keep the Exchange mount path stable. Git worktrees record absolute paths; moving the volume to another container path requires explicit repair. `AGENTOS_EXCHANGE_MOUNT_ROOT` selects the Exchange root. The default image uses `/app/data/exchange`.

The managed Git runner clears sensitive service environment variables, disables hooks and credential helpers, restricts transports and pins Git metadata paths. Ordinary setup tools may configure Husky's hooks path: service-side commands always override it. Local operations refuse executable filters, diff drivers and includes found in the shared configuration or in any worktree-specific `config.worktree`, except the exact filter written by `git lfs install`; service-side checkouts never download LFS content (`GIT_LFS_SKIP_SMUDGE=1`).

Managed clone, fetch and remote inspection use a private Git configuration context, so agent-written URL rewrites, includes, HTTP settings and credential helpers cannot influence service-account requests. The target URL is revalidated before each network operation, ambiguous numeric IPv4 spellings are rejected, and HTTP redirects are disabled. Fetch shares the managed object store and advertises known commit tips from a bounded set of private negotiation refs, together with the shallow boundary an agent's `fetch --depth` may have left, then publishes its explicitly requested tracking, case-base or observation ref in a separate operation without credentials; it refuses a concurrent ref change.

The initial bare clone imports remote branches only as `origin` tracking refs; later agent-created branches are preserved. Exchange APIs deny `.git` access, including symlink aliases. Shell-capable agents remain trusted at the service OS-user level and share a namespace's Git object store; existing Case permissions are not a filesystem sandbox.

An unused failed initial clone can have its URL or main branch corrected only while no repository has been published and no active family is bound. Audit rows of deleted, never-provisioned families do not block this correction; their snapshots remain unchanged. A failed family that is still active must first be deleted through the normal case action. Otherwise repository replacement or storage relocation remains an explicit operator action: configuration changes must not silently move a shared clone or destroy local files. Existing workspaces use the namespace's current service-account setting while preserving their initial base and setup snapshot. The creation automation switch can be changed independently.

## Exchange environment panel and diff

The Case Exchange section shows worktree location and branch, aggregate added/deleted lines, and a read-only file-by-file diff dialog implemented with diff2html. The diff is the net change from the merge-base of HEAD and the locally known origin/main branch to the working directory: committed, staged, unstaged and non-ignored untracked files are included. It does not fetch the remote on each view. Binary/link previews and oversized patches show an explanation. Renames are represented as delete/add pairs. External diff drivers and text conversion are disabled.

Inside `repo/`, the file tree uses the same branch-plus-local diff as the Changes counter. Files show `A` (added), `M` (modified), `D` (deleted), `U` (untracked), `T` (type changed), or `!` (merge conflict). Folder markers count changed descendants. Clicking a status opens that file's diff; ordinary filenames still open the content preview. Deleted files remain visible as struck-through rows without download/delete actions, including paths whose parent directories no longer exist. Shared documents and files outside `repo/` have no Git decoration. The tree and panel reuse one observation request, and background updates preserve the current directory and DOM rows. Environment and participant polling only runs while Files is visible; workspace status is shared across its consumers, with non-overlapping polls and released subscriptions when no longer observed.

The Case panel lists distinct participating agent IDs from running/finished events across the root and all descendants; selected-but-never-run agents do not count. Unreadable cases are excluded before querying events. Namespace views contain documents only, without a Git panel or case activity. The read-only endpoints use the same Case READ checks as file browsing:

- `GET /api/cases/{id}/exchange/environment`
- `GET /api/cases/{id}/exchange/diff?path=<repository-relative-file>`

Namespace files are ordinary shared documents. Repository configuration and preparation status are available in namespace Git settings.

Uploaded documents land at the outer Exchange root, outside Git. File tools address source files with `repo/…`; BASH, TMUX and opted-in MCP processes start inside `repo/`. Cleanup only removes `repo/`, leaving outer documents in place.

### Existing local data

No automatic migration is provided for older namespace checkouts. Provisioning refuses an existing checkout at `shared/.git` or `shared/repo/.git` instead of silently creating a second repository and orphaning its worktrees. Existing installations require an operator to stop the service, back up the data, relocate the common Git directory, and repair linked-worktree pointers before restarting. Git references, indexes and working files must be preserved.
