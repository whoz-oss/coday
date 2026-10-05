# Namespace Git repositories

Git is optional. A namespace administrator can associate an HTTPS repository, its main branch,
and the UUID of a namespace-shared service account from **Namespaces → Git repository**.
Namespaces without this association retain their existing behavior.

Git is available only when the `agentos-git-plugin` is loaded. The plugin registers the `GIT`
integration type, which gives agents their Git tools; its presence also enables the namespace
association. Without it the **Git** entry is hidden, the namespace Git endpoints answer 404 and
the generic integration configuration API refuses `GIT_REPOSITORY`. The association itself never
requires defining a `GIT` integration.

The `GIT_REPOSITORY` integration is a namespace capability, not an agent tool. The server enforces
one active association per namespace and validates the repository URL and branch on both the
dedicated API and generic integration configuration API. User-scoped credentials are never used
for these managed operations. Use a namespace-shared bearer token, API key, or basic-auth setting.

Saving records an asynchronous clone request. The background sweep prepares an internal bare
repository at `<exchange mount>/<namespace ID>/repository.git`. Its files are outside both
Exchange roots; namespace documents remain unchanged. States are `PREPARING`, `READY`, and
`FAILED`. Saving the settings again explicitly retries a failure. Removing the association
preserves the internal clone, which can be adopted when associating the same repository again.
Changing the repository URL or main branch of an existing checkout requires an explicit migration.

This initial capability prepares repository storage only. It does not create case worktrees,
branches, commits, or pull requests.

## Deployment

The runtime needs Git and CA certificates; the service Dockerfile installs them. Keep the Exchange
mount on persistent storage. `AGENTOS_GIT_BINARY` can pin the Git executable, and clone and command
timeouts and output limits are configurable through `agentos.git`. Only HTTPS remotes are enabled
in production. Private network remotes require `AGENTOS_GIT_ALLOW_PRIVATE_REMOTE_HOSTS=true`.
Credentials are provided through a temporary askpass helper and never embedded in the remote URL.
The runner, its isolated network commands and the remote URL validator live in the `agentos-git`
library, shared by the service and the Git plugin; the service binds the `agentos.git` settings.
Deploy the plugin JAR (`./gradlew deployPlugins`) to enable Git on an instance.
Preparing repositories needs the background worker (`AGENTOS_GIT_WORKER_ENABLED=true`, off by
default): without it, saving an association is refused, since nothing would prepare the repository.
Inherited Git configuration and hostile local configuration are rejected or isolated.

The sweep supports one AgentOS instance per workstream. Its in-process guard prevents overlapping
passes in that JVM; multiple instances sharing the same database/storage would require a lease.

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

A pause takes effect after the item in progress: a clone already running is not interrupted. It
applies to this instance only and is lost on restart. Metrics: `agentos.git.worker.sweep` (timer),
`agentos.git.worker.errors` (counter tagged by `operation`) and `agentos.git.worker.paused` (gauge).

## Workspace execution foundation

Case-family workspaces are behind `agentos.git.workspaces.enabled` (`AGENTOS_GIT_WORKSPACES_ENABLED`, off by default). The REST API and the agent's case-file tools ask an `ExchangeRootResolver` where a case's files live. While the flag is off, `DefaultExchangeRootResolver` returns each case's own date-sharded directory, exactly as before, without database access. Access to a directory owned by another case also requires permission on that owner; the default resolver never returns one.

With the flag on, `GitExchangeRootResolver` replaces it. A case whose family has a workspace binding resolves to the root case's Exchange directory; every other case keeps its own. A workspace that is not ready refuses its path rather than falling back to another directory, and file mutations on a shared directory take a per-family lock without waiting. The binding service, its repository bean and its Neo4j constraints exist only with the flag.

With the flag on, `GitCaseLaunchGate` holds a turn of an equipped family `PENDING` while its workspace is prepared and admits it once ready; without the flag no gate is installed and every run starts immediately. The message is persisted when it arrives, and nothing is replayed after a restart. When the launch check itself fails, the turn is not started: the user gets a warning and the case returns to IDLE. Input is refused while a workspace is being removed or for a closed equipped case, Stop and Kill cancel a launch admitted but not yet running, and an orderly shutdown leaves an unfinished equipped conversation open for a fresh instruction. Delegated sub-cases go through the same gate. Nothing creates a binding yet: worktree allocation comes in the next change.
