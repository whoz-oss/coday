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
Inherited Git configuration and hostile local configuration are rejected or isolated.

The sweep supports one AgentOS instance per workstream. Its in-process guard prevents overlapping
passes in that JVM; multiple instances sharing the same database/storage would require a lease.

## Workspace execution foundation

Family directory resolution, workspace readiness checks and workspace-aware tool contexts are installed. Execution uses the existing AgentOS runtime without a durable command journal. Namespace options and worktree allocation are introduced in the next change. Existing unequipped cases retain their per-case Exchanges and execution path.
