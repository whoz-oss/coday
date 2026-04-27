# agentos-gradle

Local Nx plugin that infers all common targets and release configuration for Gradle
subprojects under `agentos/`.

## How it works

The plugin uses `createNodesV2` to match `agentos/*/build.gradle.kts`. For each match that
also has a `project.json` (i.e. is a registered Nx project), it infers:

- **`build`** — runs `./gradlew :<projectName>:build`, cached, depends on `agentos-sdk:build`
- **`test`** — runs `./gradlew :<projectName>:test`, cached, depends on `build` + `agentos-sdk:build`
- **`clean`** — runs `./gradlew :<projectName>:clean`, not cached
- **`release`** — sets `manifestRootsToUpdate: []` so Nx release does not look for a `package.json`
- **`nx-release-publish`** — no-op target satisfying `releasePublish()` in `scripts/release.ts`; actual publishing is done by Gradle in CI
- **`publish`** — runs `./gradlew :<projectName>:publish` from the `agentos/` directory, depends on `build`

All inferred targets share the same standard inputs: `default`, `build.gradle.kts`,
`agentos/gradle.properties`, and `agentos/settings.gradle.kts`.

The project name is derived from the directory name, which matches the Gradle subproject name
by convention in this workspace.

## Overriding inferred targets

Explicit targets in `project.json` take priority over inferred ones. Use this for:

- **Removing `agentos-sdk` from `dependsOn`** (as done in `agentos-sdk/project.json` itself)
- **Overriding `publish`** with a no-op for legacy projects (as done in `agentos-plugins-filesystem/project.json`)
- **Adding project-specific targets** like `bootRun`, `bootJar`, `check-openapi-spec`

## Adding a new Gradle artifact

No plugin changes needed. Just ensure the new subproject:
1. Has a `build.gradle.kts` under `agentos/<project-name>/`
2. Has a `project.json` (so it is a registered Nx project)
3. Is tagged `platform:jvm` (so it is picked up by `release.projects` in `nx.json` and the validate CI step)

## Running unit tests

```bash
nx test agentos-gradle
```
