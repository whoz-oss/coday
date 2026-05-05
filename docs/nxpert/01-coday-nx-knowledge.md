# Coday Nx Workspace Knowledge

## Workspace Overview

- **Polyglot monorepo**: Angular frontend (`apps/client`), Node.js server (`apps/server`),
  Electron desktop apps (`apps/desktop`, `apps/desktop-twin`), web bundle (`apps/web`),
  TypeScript libs (`libs/`), Kotlin/Gradle sub-workspace (`agentos/`)
- **Package manager**: pnpm with centralized version catalog in `pnpm-workspace.yaml` —
  dep version changes go there, not in individual `package.json`
- **`defaultBase` branch**: `master` — affects all `nx affected` commands and CI
- **TUI disabled** in `nx.json`; local filesystem cache only
- **Daemon**: enabled (`useDaemonProcess: true`)

## Inferred vs Explicit Targets

Some targets are **not declared in `project.json`** — they are inferred by plugins registered
in `nx.json`. For example, `test` is inferred by `@nx/jest/plugin` on any project that has a
`jest.config.*`, and `lint` is inferred by `@nx/eslint/plugin`.

**Always use `nx_project_details` to see the full effective target list for any project** —
do not assume from `project.json` alone.

As a general orientation: `test` and `lint` are typically inferred; `build` and special targets
like `check-openapi-spec`, `bootRun`, `generate-client` are explicitly declared in `project.json`.

## JVM Sub-workspace (`agentos/`)

The `agentos/` directory is a separate Gradle multi-project build integrated into Nx via
`nx:run-commands`. Publishable projects carry the `platform:jvm` tag — use
`nx show projects --projects="tag:platform:jvm"` to get the current list.
`agentos-plugins-filesystem` is a legacy project — it has no `platform:jvm` tag and is not published.

Nx integration is handled by a **local plugin** at `tools/plugins/agentos-gradle/src/agentos-gradle.ts`
(not `@nx/gradle`). It globs `agentos/*/build.gradle.kts` and infers `build`, `test`, `clean`,
`nx-release-publish`, `publish` targets only for projects that already have a `project.json`.

Key patterns:
- All Gradle targets set `"cwd": "agentos"` in options
- Gradle global inputs: `agentos/gradle.properties`, `agentos/settings.gradle.kts`
- `agentos-service` depends on `agentos-sdk:build` before its own build
- `agentos-service:regenerate` is the full OpenAPI pipeline: `generate-openapi-spec` → `generate-client`

## TypeScript Library Architecture

~30 TypeScript libs under `libs/` with two import namespaces:
- `@coday/*` — core Coday framework libs (e.g. `@coday/core`, `@coday/model`, `@coday/mcp`)
- `@whoz-oss/*` — AgentOS UI libs (`@whoz-oss/agentos-ui`, `@whoz-oss/agentos-dataflow`,
  `@whoz-oss/design-system`, `@whoz-oss/agentos-api-client`)

Nested libs exist under `libs/handlers/` (`config`, `load`, `looper`, `memory`, `openai`, `stats`),
each with their own `project.json`. Import paths follow the pattern `@coday/handlers-<name>`.

## Tag & Module Boundary Conventions

Two-dimension tag system: `scope:<domain>` + `type:<layer>`

**Scopes**: `libs`, `agentos`, `agentos-dataflow`, `design-system`  
**Types**: `core`, `model`, `integration`, `ui`, `api-client`, `utils`, `app`, `tool`  
**JVM**: additionally tagged `platform:jvm` + `scope:service` / `scope:sdk` / `scope:plugin`

Boundary rules enforced in root `eslint.config.js`:
- `scope:design-system` → only `type:model`, `type:utils`
- `scope:agentos-dataflow` → only `type:model`, `type:utils`, `type:api-client`
- `scope:agentos` (UI) → `scope:design-system`, `scope:agentos-dataflow`, `type:api-client`, `type:model`, `type:utils`
- All other coday libs → no restriction (open)

## CI Architecture

Two GitHub Actions workflows in `.github/workflows/`:
- **`validate.yml`**: PR validation — runs `nx affected --target="lint,test"` + `check-openapi-spec`.
  JVM projects are handled separately: `nx show projects --affected --projects="tag:platform:jvm" --json --quiet`
  resolves affected names first, then `nx run-many` is called with explicit names (tag filter can't
  be forwarded to Gradle executors directly).
- **`release.yml`**: Push to master — 4 jobs in sequence:
  1. `check-release` — detects releasable commits since last tag
  2. `release` — runs `nx release` (version bump, changelog, GitHub release, npm publish for Node projects)
  3. `desktop-release` — macOS runner, signs and packages `.pkg` for `tag:platform:node` desktop apps, uploads to GitHub Release
  4. `compute-jvm-projects` — resolves `tag:platform:jvm` projects via `nx show projects --json --quiet | jq -c '.'`
  5. `publish-agentos-artifacts` — matrix job over the dynamic list, publishes each JVM project to GitHub Packages

## Release System

Uses `nx release` with conventional commits. Tag pattern: `release/{version}`.
- **Node projects** selected via `tag:platform:node` in `nx.json` release config
- **JVM projects** selected via `tag:platform:jvm` — `agentos-plugins-filesystem` explicitly does NOT carry this tag (legacy, not published)
- Adding a new publishable project only requires the right `platform:*` tag — no CI config changes needed

## Cache Debugging Checklist

1. Use `nx_project_details` with `select: "targets.<name>"` to inspect declared inputs/outputs
2. Missing `outputs` = no file restoration from cache
3. For inferred targets (`test`, `lint`), the effective config merges `nx.json` `targetDefaults`
   with whatever the plugin infers — check both
