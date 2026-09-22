# Namespace Git repositories

Git is optional. A namespace administrator can associate an HTTPS repository, its main branch,
and the UUID of a namespace-shared service account from **Namespaces → Git repository**.
Namespaces without this association retain their existing behavior.

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
Inherited Git configuration and hostile local configuration are rejected or isolated.

The sweep supports one AgentOS instance per workstream. Its in-process guard prevents overlapping
passes in that JVM; multiple instances sharing the same database/storage would require a lease.
