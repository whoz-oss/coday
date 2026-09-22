# Git repository storage foundation

This stage provides the bounded Git runner, namespace service-account resolver, internal bare
repository preparation, checkout persistence and singleton integration storage.

The runner, its isolated network commands and the remote URL validator live in the `agentos-git`
library rather than in the service, so a plugin can reuse the same hardened execution. The library
has no Spring components; the service binds `agentos.git` settings and declares its beans.

It introduces no Git settings page, namespace Git endpoint, provisioning timer or case worktree.
Existing cases and Exchanges keep their behavior. The next stage connects the optional namespace
association to this foundation; no clone request is produced by this stage.

The internal repository location is `<exchange mount>/<namespace ID>/repository.git`, outside
both Exchanges. Managed commands use explicit paths, isolated configuration and bounded output.
Keep the Exchange mount persistent. The deployment image supplies Git and CA certificates.

The screenshots under `docs/assets/git-workspaces/` illustrate the completed feature; the UI
shown there is delivered by later stages.
