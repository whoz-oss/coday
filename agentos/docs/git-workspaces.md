# Optional namespace Git workspaces

## Product contract

Git is an optional namespace integration. With no `GIT_REPOSITORY` association, cases, file Exchanges and tool configuration retain their existing behavior. Associating a repository prepares an internal **bare repository** (Git objects and references, without a shared checkout) at `<mountRoot>/<namespaceId>/repository.git`, outside both Exchange roots. The Namespace Exchange holds shared documents only. The separate `autoWorktreeForRootCases` option defaults to false.

Git is available only when the `agentos-git-plugin` is loaded. The plugin registers the `GIT` integration type, which gives agents their Git tools; its presence also enables the namespace association. Without it the **Git** entry is hidden, the namespace Git endpoints answer 404, the generic integration configuration API refuses `GIT_REPOSITORY`, and no new root case is equipped. Families equipped earlier keep their worktree, tools and cleanup. Associating a repository never requires defining a `GIT` integration.

When enabled, each **new root case** gets a worktree in `repo/` under its Case Exchange root. All descendants share the entire Case Exchange, including documents outside Git. Existing families are never retroactively equipped, and disabling automation does not disconnect existing workspaces. Persisted bindings retain the settings used to create them.

**AgentOS does not create, name or rename working branches or pull requests.** A new worktree starts with a detached HEAD at the configured main branch's fetched commit. Agents create branches and PRs using their workflow tools. The case title has no effect on Git. Retrying preparation preserves any branch or local work already created by an agent.

The root case owns the durable workspace. Killing or replacing a contributor conversation does not delete it. Factory controllers and contributors can be sub-cases of this durable root.

To create a sub-case manually, use **Create sub-case** in a writable root case's actions (desktop, compact sidebar or mobile). The composer shows the selected parent; sending its first message creates the child with `parentCaseId`. The child appears under that parent and uses the family's existing worktree. This action also works in namespaces without Git.

## Provisioning and recovery

Case creation and the resource binding are recorded in a short database transaction. A background worker prepares the internal repository, fetches the configured remote main branch, freezes a per-case Git reference (`refs/agentos/base/<root-case-id>`), records the SHA in Neo4j, creates the detached worktree and runs the optional setup command there. No agent or file mutation is admitted before `READY`. Errors are visible and retryable; they never fall back to another directory.

Every new root case fetches its base from the remote, independently of existing local branches and worktrees. Existing cases keep their frozen base and local changes; updating them with merge/rebase remains an agent workflow responsibility. Fetch does not merge files, so there is no shared checkout to synchronize or resolve conflicts in. A failed fetch blocks provisioning instead of using a stale local base.

A crashed fetch is reconciled from the internal base reference. A setup marked as started but not completed requires explicit acknowledgement before replay, because installation scripts may have side effects. `FAILED` is not automatically retried. Saving the namespace Git settings explicitly requeues a failed internal clone. The dedicated Git settings endpoint and generic integration configuration CRUD share this validation and preparation policy.

Agent execution uses the existing AgentOS runtime and in-memory command queue. Messages are stored through the ordinary conversation event path. Runs wait while their worktree is being prepared, then start when it is ready. Server restarts do not replay deferred or interrupted instructions. The worktree is retained; the existing AgentOS lifecycle and terminal-status rules apply (KILLED/ERROR cases remain terminal in the UI). There is no additional command journal, HTTP request deduplication, or execution recovery action. Scheduled prompts use the existing scheduler behavior.

When case files are opened during worktree preparation, the drawer shows a spinner and an explanation instead of a file-loading error. It shares workspace status with the conversation banner and namespace list, and loads the files automatically when ready. Failed preparation remains an error, and leaving the case cancels the wait. Namespaces and cases without Git keep their normal file loading behavior.

The conversation banner is reserved for preparation, failure and cleanup notices; preparation retry remains available when Files is closed.

Provisioning and lifecycle coordination target **one AgentOS instance per workstream**. They are not a distributed lease protocol. Ordinary concurrent edits by agents sharing a worktree remain a workflow responsibility.

## Tools and configuration

The built-in Exchange integration and REST API use `ExchangeRootResolver`. Effective BASH/TMUX/MCP_STDIO configuration uses the same resolved directory:

| Integration | Effective parameter |
| ----------- | ------------------- |
| BASH, TMUX  | `workingDirectory`  |
| MCP_STDIO   | `cwd`               |
| GIT         | `workingDirectory`, plus `gitDir`, `commonGitDir`, `repositoryUrl` and `mainBranch` |

These are per-run copies; saved integrations are not rewritten. For BASH and TMUX, `useCaseExchangeDirectory` defaults to true and can be disabled for an integration intentionally targeting another directory; their shells also use the family's workspace support directory as `HOME` (with `XDG_CACHE_HOME` below it), the same one setup used, so package-manager stores and daemons match what setup installed and are never shared between families. MCP_STDIO enters the workspace only when `useCaseExchangeDirectory` is explicitly true: agents do not drive such a server, it often holds credentials in its environment, and project files in the agent-writable worktree (such as `.npmrc`) could run code inside it. A namespace without a Git workspace keeps the configured directory.

A `GIT` integration only exists inside a Git workspace. It always targets the family's worktree, with the administrative directory pinned from the binding rather than read from the worktree's `.git` file, and the repository URL and main branch recorded when the family was equipped. Saved values never override this context. Outside a Git workspace, or for a user without write access to it, agents receive no Git tool.

TMUX uses a distinct socket for each root workspace, shared by descendants. MCP connections are keyed by configuration, including the resolved working directory. Cleanup releases workspace TMUX/MCP resources and tracked Bash processes. Bash commands preserve normal background-job semantics: redirecting a background job’s output allows the shell command to return. Tracked descendants can still be stopped during workspace cleanup; scoped TMUX tools are available for persistent development sessions. Before deletion, an `lsof` scan also checks for processes holding a file or working directory inside the worktree, including detached jobs or jobs surviving an AgentOS restart. Missing or inconclusive process inspection blocks cleanup. These tools remain trusted shell execution, not an OS sandbox.

The namespace service account is used for managed clone/fetch. Agents' workflow tools and forge integrations remain responsible for branch creation, push and PR creation, using their configured authentication. No branch or PR creation endpoint is provided by the workspace feature.

## Case deletion and worktree cleanup

AgentOS keeps its existing deletion behavior: stop the case's in-memory execution, mark it `removed` in Neo4j and hide it from normal listings. Events remain stored. Deleting a root does not delete its descendants. There is no separate finalisation or archive workflow, and closing or merging a PR has no effect on cases or worktrees.

The workspace worker observes this existing deletion marker and removes the local worktree only after every case in its family has been deleted and executions/tools have stopped. Surviving sub-cases continue using the same shared Exchange, even after deletion of the root case. Deleting a case without Git remains unchanged. A worktree that holds another linked worktree (for example one an agent created in an ignored directory) is kept until that worktree is removed. A removal interrupted midway, for example by a redeploy, is completed on a later pass when the service's own removal marker is present and the commit retained before removal is still the worktree's HEAD.

Cleanup uses `git worktree remove` without force. Git refusal (for example, local uncommitted files or a locked worktree) leaves the worktree on disk and records the reason; it never prevents case deletion. A later worker pass retries cleanup. Only `repo/` is removed: documents beside it, messages in Neo4j, and local/remote branches are retained. No PR lookup or merge hook is involved.

## HTTP endpoints

All existing Case/Namespace permission checks apply.

- `GET /api/cases/{caseId}/workspace`
- `GET /api/namespaces/{namespaceId}/workspaces`
- `POST /api/cases/{rootCaseId}/workspace/retry`

`retry` belongs to the root case and accepts `acknowledgeSetupReplay` (default false). Deletion continues to use the existing Case DELETE endpoint.

## Deployment

Managed workspaces require Git and CA certificates; cleanup also requires `lsof`, and repositories using Git LFS require `git-lfs`. The service Dockerfile adds these runtime dependencies and a writable data directory. Repository-specific toolchains and plugin deployment follow the existing deployment configuration.

Deploy the `agentos-git-plugin` JAR with the other plugins (`./gradlew deployPlugins` locally) to enable Git on an instance. Server-side Git runs through the `agentos-git` library, shared by the service and the plugin; the service binds the `agentos.git` settings.

Mount persistent storage for `/app/data` (Neo4j and Exchange), and keep the Exchange mount path stable. Git worktrees record absolute paths; moving the volume to another container path requires explicit repair. `AGENTOS_EXCHANGE_MOUNT_ROOT` selects the Exchange root. The default image uses `/app/data/exchange`.

The managed Git runner clears sensitive service environment variables, disables hooks and credential helpers, restricts transports and pins Git metadata paths. Exchange APIs deny `.git` access, including symlink aliases. Shell-capable agents remain trusted at the service OS-user level and share a namespace's Git object store; existing Case permissions are not a filesystem sandbox.

Repository URL replacement or storage relocation remains an explicit operator action: configuration changes must not silently move a shared clone or destroy local files. The creation automation switch can be changed independently.

## Documents and repository files

Uploaded documents land at the outer Exchange root, outside Git. File tools address source files with `repo/…`; BASH, TMUX and opted-in MCP processes start inside `repo/`. Cleanup only removes `repo/`, leaving outer documents in place.

### Existing local data

No automatic migration is provided for older namespace checkouts. Provisioning refuses an existing checkout at `shared/.git` or `shared/repo/.git` instead of silently creating a second repository and orphaning its worktrees. Existing installations require an operator to stop the service, back up the data, relocate the common Git directory, and repair linked-worktree pointers before restarting. Git references, indexes and working files must be preserved.
