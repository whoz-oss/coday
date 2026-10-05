# Optional namespace Git workspaces

## Product contract

Git is an optional namespace integration. With no `GIT_REPOSITORY` association, cases, file Exchanges and tool configuration retain their existing behavior. Associating a repository prepares an internal **bare repository** (Git objects and references, without a shared checkout) at `<mountRoot>/<namespaceId>/repository.git`, outside both Exchange roots. The Namespace Exchange holds shared documents only. The separate `autoWorktreeForRootCases` option defaults to false.

Git is available only when the `agentos-git-plugin` is loaded. The plugin registers the `GIT` integration type, and its presence enables the namespace association. Without it the **Git** entry is hidden, the namespace Git endpoints answer 404, the generic integration configuration API refuses `GIT_REPOSITORY`, and no new root case is equipped. Families equipped earlier keep their worktree, tools and cleanup. Associating a repository never requires defining a `GIT` integration.

Case-family workspaces are behind `agentos.git.workspaces.enabled` (`AGENTOS_GIT_WORKSPACES_ENABLED`, off by default). While it is off, every case keeps its own Exchange directory through `DefaultExchangeRootResolver`, which returns the same directory as before the flag existed, runs start immediately, no case is equipped, the workspace beans and endpoints are not loaded, and the binding service, its repository bean and its Neo4j constraints do not exist. The namespace association and its internal clone do not depend on this flag.

When enabled, each **new root case** gets a worktree in `repo/` under its Case Exchange root. All descendants share the entire Case Exchange, including documents outside Git. Existing families are never retroactively equipped, and disabling automation does not disconnect existing workspaces. Persisted bindings retain the settings used to create them.

**AgentOS does not create, name or rename working branches or pull requests.** A new worktree starts with a detached HEAD at the configured main branch's fetched commit. Agents create branches and PRs using their workflow tools. The case title has no effect on Git. Retrying preparation preserves any branch or local work already created by an agent.

The root case owns the durable workspace. Killing or replacing a contributor conversation does not delete it. Factory controllers and contributors can be sub-cases of this durable root.

To create a sub-case manually, use **Create sub-case** in a writable root case's actions (desktop, compact sidebar or mobile). The composer shows the selected parent; sending its first message creates the child with `parentCaseId`. The child appears under that parent and uses the family's existing worktree. This action also works in namespaces without Git.

## Provisioning and recovery

Case creation and the resource binding are recorded in a short database transaction. A background worker prepares the internal repository, fetches the configured remote main branch, freezes a per-case Git reference (`refs/agentos/base/<root-case-id>`), records the SHA in Neo4j, creates the detached worktree and runs the optional setup command there. No agent or file mutation is admitted before `READY`. Errors are visible and retryable; they never fall back to another directory.

Every new root case fetches its base from the remote, independently of existing local branches and worktrees. Existing cases keep their frozen base and local changes; updating them with merge/rebase remains an agent workflow responsibility. Fetch does not merge files, so there is no shared checkout to synchronize or resolve conflicts in. A failed fetch blocks provisioning instead of using a stale local base.

A crashed fetch is reconciled from the internal base reference. A setup marked as started but not completed requires explicit acknowledgement before replay, because installation scripts may have side effects. `FAILED` is not automatically retried. Saving the namespace Git settings explicitly requeues a failed internal clone. The dedicated Git settings endpoint and generic integration configuration CRUD share this validation and preparation policy.

Agent execution uses the existing AgentOS runtime and in-memory command queue. Messages are stored through the ordinary conversation event path. Runs wait while their worktree is being prepared, then start when it is ready. When the launch check itself fails (for example a transient database error), the turn is not started: the user gets a warning and the case returns to IDLE, and can send the message again. Server restarts do not replay deferred or interrupted instructions. The worktree is retained; the existing AgentOS lifecycle and terminal-status rules apply (KILLED/ERROR cases remain terminal in the UI). There is no additional command journal, HTTP request deduplication, or execution recovery action. Scheduled prompts use the existing scheduler behavior.

When case files are opened during worktree preparation, the drawer shows a spinner and an explanation instead of a file-loading error. It shares workspace status with the conversation banner and namespace list, and loads the files automatically when ready. Failed preparation remains an error, and leaving the case cancels the wait. Namespaces and cases without Git keep their normal file loading behavior.

The conversation banner is reserved for preparation, failure and cleanup notices; preparation retry remains available when Files is closed.

Provisioning and lifecycle coordination target **one AgentOS instance per workstream**. They are not a distributed lease protocol. Ordinary concurrent edits by agents sharing a worktree remain a workflow responsibility.

## Tools and configuration

The built-in Exchange integration and REST API use `ExchangeRootResolver`: `DefaultExchangeRootResolver` while the flag is off, `GitExchangeRootResolver` when it is on. Admission goes through `CaseLaunchGate`, installed only with the flag.

A `GIT` integration only exists inside a Git workspace. It always targets the family's worktree, with the administrative directory pinned from the binding rather than read from the worktree's `.git` file, and the repository URL and main branch recorded when the family was equipped. `GitToolsRunIntegration` gives it, on per-run copies, the worktree as `workingDirectory` plus `gitDir`, `commonGitDir`, `repositoryUrl` and `mainBranch`; saved values never override this context and the saved integration is not rewritten. Outside a Git workspace, for a user without write access to it, or on an instance without `agentos.git.workspaces.enabled`, agents receive no Git tool.

Before deleting a worktree, cleanup stops the processes still using it, found with `lsof`: background jobs, tmux shells or MCP servers started there, including jobs surviving an AgentOS restart. They receive SIGTERM, then SIGKILL after 5 seconds. Only processes of the service's own OS user are touched, never the service itself. Cleanup then checks again that nothing holds a file or working directory in the worktree; missing or inconclusive inspection blocks it. An MCP connection stopped this way is recreated at its next use. These tools remain trusted shell execution, not an OS sandbox.

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

Managed Git runs on Linux or macOS only: it relies on a POSIX shell, POSIX permissions and `lsof`. Deploy the `agentos-git-plugin` JAR with the other plugins (`./gradlew deployPlugins` locally) to enable Git on an instance. Server-side Git runs through the `agentos-git` library, shared by the service and the plugin; the service binds the `agentos.git` settings, while the agents' Git tools in the plugin use the default limits and the integration's own `allowPrivateRemoteHosts` flag. Also enable the background worker with `AGENTOS_GIT_WORKER_ENABLED=true`: it prepares repositories and, with `AGENTOS_GIT_WORKSPACES_ENABLED=true`, worktrees and their cleanup. Both are off by default. The service logs a notice when the GIT plugin is loaded without the worker, saving a repository association is refused without it, and no case is equipped while workspaces are enabled without it: nothing would prepare their worktree. Its metrics, `agentos.git.worker.sweep` (timer) and `agentos.git.worker.errors` (counter tagged by operation), are exposed through Actuator.

Only HTTPS remotes are allowed by default. Private network remotes (a self-hosted forge) require `AGENTOS_GIT_ALLOW_PRIVATE_REMOTE_HOSTS=true`. Managed clone and fetch authenticate with the namespace-shared service account, through a temporary askpass helper: credentials are never embedded in the remote URL. `AGENTOS_GIT_BINARY` pins the Git executable; the other `agentos.git` settings are listed in `GitExecutionProperties`.

The setup command runs with a cleared environment, so service secrets are not readable from it. Anyone able to push a branch can still run code through dependency lifecycle scripts: prefer a command that disables them, such as `npm ci --ignore-scripts` or `pnpm install --ignore-scripts`.

Workspace-backed execution is single-instance **by construction**, not just by configuration.
`WorkspaceLifecycleLocks` is a JVM singleton (`object`), and the admission model (`deferredRuns`,
`executionJobs`, `whenAvailable` callbacks) is entirely in-process. Horizontal scaling would
require a distributed lease *and* persisting the intent to run — which reopens the deliberate
"nothing is replayed after a restart" trade-off.

The sweep runs on a dedicated `git-workspace` thread pool, never on Spring's scheduler thread, so a
long clone does not delay other scheduled work. An operator can pause it on a live instance through
the `gitworkspaces` Actuator endpoint, registered only with the worker, over HTTP or JMX:

- `GET /management/gitworkspaces` returns whether the worker is paused, and the item
  in progress with its start time.
- `POST /management/gitworkspaces/{phase}` with `{"action": "pause"}` or `{"action": "resume"}`,
  where `phase` is `provisioning` or `all`.

A pause takes effect after the item in progress: a clone, worktree or setup already running is not
interrupted. It applies to this instance only and is lost on restart. The
`agentos.git.worker.paused` gauge reports it.

Mount persistent storage for `/app/data` (Neo4j and Exchange), and keep the Exchange mount path stable. Git worktrees record absolute paths; moving the volume to another container path requires explicit repair. `AGENTOS_EXCHANGE_MOUNT_ROOT` selects the Exchange root. The default image uses `/app/data/exchange`.

The managed Git runner clears sensitive service environment variables, disables hooks and credential helpers, restricts transports and pins Git metadata paths. Exchange APIs deny `.git` access, including symlink aliases. Shell-capable agents remain trusted at the service OS-user level and share a namespace's Git object store; existing Case permissions are not a filesystem sandbox.

Repository URL replacement or storage relocation remains an explicit operator action: configuration changes must not silently move a shared clone or destroy local files. The creation automation switch can be changed independently. Without workspaces, a failed first clone that was never published can still be pointed at another repository. Families recorded while workspaces were on keep the repository they were created with and can only be deleted.

## Documents and repository files

Uploaded documents land at the outer Exchange root, outside Git. File tools address source files with `repo/…`. Cleanup only removes `repo/`, leaving outer documents in place.

### Existing local data

No automatic migration is provided for older namespace checkouts. Provisioning refuses an existing checkout at `shared/.git` or `shared/repo/.git` instead of silently creating a second repository and orphaning its worktrees. Existing installations require an operator to stop the service, back up the data, relocate the common Git directory, and repair linked-worktree pointers before restarting. Git references, indexes and working files must be preserved.
