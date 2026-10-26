# gradle-plugins

Local Nx plugins that infer targets and release configuration for all Gradle subprojects
in the monorepo. Two handlers are registered, one per Gradle sub-workspace:

| Handler | Source | Glob | Sub-workspace |
|---|---|---|---|
| AgentOS | `src/agentos-gradle.ts` | `agentos/*/build.gradle.kts` | `agentos/` |
| Factory | `src/factory-gradle.ts` | `factory/*/build.gradle.kts` | `factory/` |

Both are registered as separate plugin entries in `nx.json` — `src/index.ts` is the
entry point for the AgentOS handler, `src/factory-gradle.ts` directly for the Factory
handler.

---

## AgentOS handler (`src/agentos-gradle.ts`)

For each `agentos/*/build.gradle.kts` that also has a `project.json`, infers:

- **`build`** — `./gradlew :<name>:build`, cached, depends on `agentos-sdk:build`
- **`assemble`** — `./gradlew :<name>:assemble`, cached, depends on `agentos-sdk:assemble`
- **`test`** — `./gradlew :<name>:test`, cached, depends on `build` + `agentos-sdk:build`
- **`clean`** — `./gradlew :<name>:clean`, not cached
- **`nx-release-publish`** — no-op echo; satisfies `releasePublish()`; real publish is done by Gradle in CI
- **`publish`** — `./gradlew :<name>:publish`, depends on `assemble`
- **`release.version.manifestRootsToUpdate: []`** — suppresses the global `apps/{projectName}` lookup

All inferred targets share Gradle inputs: `default`, `build.gradle.kts`,
`agentos/gradle.properties`, `agentos/settings.gradle.kts`.

### Overriding inferred targets

Explicit targets in `project.json` take priority. Common overrides:

- **Remove `agentos-sdk` from `dependsOn`** — as in `agentos-sdk/project.json` itself
- **Make `publish` a no-op** — for legacy projects like `agentos-plugins-filesystem`
- **Add project-specific targets** — `bootRun`, `bootJar`, `check-openapi-spec`, `regenerate`

### Adding a new AgentOS Gradle project

1. Add `build.gradle.kts` under `agentos/<project-name>/`
2. Add a `project.json` (registers the project with Nx)
3. Tag it `platform:jvm-agentos` (picks it up for release and CI)

No plugin changes needed.

---

## Factory handler (`src/factory-gradle.ts`)

For each `factory/*/build.gradle.kts` that also has a `project.json`, infers:

- **`nx-release-publish`** — no-op echo; satisfies `releasePublish()`; real publish is done by Gradle in CI
- **`release.version.manifestRootsToUpdate: []`** — suppresses the global `apps/{projectName}` lookup

Factory `build`, `test`, `clean`, `assemble` targets are **not** inferred — they are
declared explicitly in each project's `project.json` because dependency chains differ
per project (e.g. `factory-service` depends on both `factory-sdk` and
`factory-verification-core`, while `factory-sdk` depends on nothing).

### Adding a new Factory Gradle project

1. Add `build.gradle.kts` under `factory/<project-name>/`
2. Add a `project.json` with explicit `build`, `test`, `clean`, `assemble` targets and
   the correct `dependsOn` for that project
3. Tag it `platform:jvm-factory` (picks it up for release and CI)

No plugin changes needed.

---

## Running unit tests

```bash
nx test gradle-plugins
```
