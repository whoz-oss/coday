# AGENTS.md

For AI coding agents (Codex, Claude Code, Cursor, Gemini CLI...) and humans working in this repository.
This file is a hub: what applies everywhere, then where to read more. Keep it under 12,000 characters so
it loads whole; long form belongs in the docs it points to.

## What AgentOS is

AgentOS is a multi-agent orchestration platform. A Kotlin / Spring Boot service runs agents on any AI provider,
equips them with tools through plugins and configured integrations, and records every conversation (a **case**)
as a stream of events. Agents can redirect a conversation to another agent or delegate work to it in a
**sub-case**. Configuration lives in **namespaces** (multi-tenancy), with personal overlays per user, and can
also be loaded read-only from a folder (a namespace's `configPath`). An Angular UI and a Node server sit in
front of the REST + SSE API.

Invariants that shape almost every design decision; use them as the lens for any change:

- **The SDK is the contract.** Plugins depend only on `agentos-sdk` (`compileOnly`, no Spring). New capability
  arrives at the edges first: a plugin, an integration type, a skill or configuration, before a core service change.
- **Model-facing text is product surface.** Tool names and descriptions, prompt blocks and the errors returned to an
  agent are sent to the model and paid on every call. Keep them short, accurate and deterministic within a run
  (prompt caching), and make every error name the next step.
- **Namespaces and users are isolation boundaries.** Every read and write is scoped and permission-checked.
  Credentials live in auth settings: never log, return, serialize or echo them.
- **Generated artifacts are never hand-edited:** the OpenAPI spec, the TypeScript API client.

## Development environment

- Node 22+, pnpm, JDK 25 (Kotlin 2.3, Spring Boot 3.5). Nx drives both the TypeScript and the Gradle projects.
- `pnpm install` once (it also installs the git hooks: commitlint on commit messages, Prettier on staged files).
- Run the service: `nx bootRun agentos-service`. Environment variables, profiles and filesystem configuration:
  `agentos/docs/running.md`.
- Plugins: from `agentos/`, `./gradlew deployPlugins` builds every plugin and copies exactly those JARs into
  `plugins/`. PF4J loads whatever is in that folder, so redeploy after switching branches.
- Web UI against a running service: `pnpm web:dev` (client + server); point the server at the service with
  `AGENTOS_URL`.
- Tests: `nx test agentos-service`, `nx test agentos-sdk`, `nx test <project>`. CI runs
  `nx affected -t lint,test` on TypeScript projects, build + test on JVM projects, and `check-openapi-spec`.
- API change: `pnpm regen` (spec, then client), then review the generated diff and keep only what your change explains.

## Project structure

The filesystem is canonical; these are the load-bearing entry points.

```
agentos/                     # AgentOS backend, Gradle composite build
├── agentos-sdk/             # plugin contract: interfaces, domain models, API DTOs (no Spring)
├── agentos-service/         # Spring Boot app: orchestration, REST + SSE API, persistence, plugin loading
├── agentos-*-plugin/        # bundled tool plugins: bash, file, git, http, mcp, tmux, datetime
├── agentos-plugins-filesystem/  # loads agents, integrations, skills, prompts, models from a configPath
├── openapi/                 # generated OpenAPI spec (never edit)
└── docs/                    # architecture, plugins, testing, persistence, ADRs
factory/                     # workflow factory built on AgentOS (service, SDK, plugin)
libs/agentos-ui/             # Angular UI of AgentOS
libs/agentos-api-client/     # generated TypeScript client (never edit src/lib)
libs/agentos-dataflow/, libs/design-system/, libs/desktop-core/
apps/client/                 # Angular web app      apps/server/  Node server: serves the client, proxies the API
apps/desktop/, apps/web/, apps/factory-cockpit/
agents/, integrations/, prompts/, ai-models/   # this repository's own AgentOS configuration
product/                     # vision, domain model, glossary, guidelines
docs/                        # user documentation
```

In `agentos-service`, find code by package (`agentos/docs/architecture.md` has the map): `agent`, `caseFlow`,
`caseEvent`, `tool`, `plugin`, `namespace`, `entity`, `configAgent`, `delegation`, `exchange`...

## Rules that apply everywhere

- **Read before you change.** Read the code you touch, its callers and tests, and the doc for that area. Before
  "fixing" something, check why it is that way (`git log -p -S "<symbol>"`): an absence or an isolation is often
  the design.
- **Reproduce first.** A bug fix starts with the failing behaviour reproduced, and ships with one or two tests that
  fail on the base and pass with the fix.
- **Kotlin:** follow `agentos/docs/code-style.md`. Immutability (`val`, data classes), explicit nullability,
  coroutines for async work, `require` / `check` for preconditions. KDoc explains the contract and the why, not the
  syntax. No defensive clutter: no wrappers or catch-alls around code that cannot fail, no flags nobody sets.
- **Tests:** `agentos/docs/testing.md`. Kotest `StringSpec` + MockK, `*UnitSpec` without Spring, `*IntegrationSpec`
  with it. Test the public behaviour, mock at the boundary, never the class under test. Persistence contracts run
  against the real adapter: an idealised mock hides driver types and missing relations.
- **REST API:** controllers extend the entity framework (`agentos/docs/entity-framework.md`), check permissions with
  `@PreAuthorize`, and shape the spec only through springdoc annotations (`agentos/docs/openapi-and-client.md`).
- **Plugins and integrations:** `agentos/docs/plugin-system.md`. A plugin bundles its own dependencies; it never
  reaches into service internals. Filesystem configuration is read-only for the platform: tokens such as
  `{{NAMESPACE_CONFIG_PATH}}` are resolved at load time, `{{CASE_EXCHANGE}}` and `{{NAMESPACE_EXCHANGE}}` per run.
- **TypeScript / Angular:** Prettier (no semicolons, single quotes, 120 columns), strict types, `??` over `||` for
  defaults, Angular control flow (`@if`, `@for` with `track`). Follow the patterns of the library you touch.
- **Dependencies:** check the version, current advisories and licence; pin through the version catalog
  (`agentos/gradle/libs.versions.toml`) or the lockfile.
- **Security:** no secrets in commits, logs, errors, `toString`, fixtures or screenshots. A change that crosses a
  permission or tenancy boundary comes with a test of the refusal, not only of the success.
- **Git:** check `git status` before editing and preserve work you did not make. The stash is shared by every
  worktree of a clone: never pop someone else's entry. Stage only intended files.
- **Commits and PRs:** Conventional Commits (enforced by commitlint), in English. One concern per PR; the description
  says what changed, why, and how it was verified, including what was not tested. No AI-attribution trailers.
  Branch naming and review expectations: `product/04-guidelines.md`.
- **Finish honestly.** Report what was done, what was verified and how, and what is left. Never present a step as
  done before it is verified.

## Read when relevant

| Working on | Read |
|---|---|
| Module layout, package map | `agentos/docs/architecture.md` |
| Plugins, tools, filesystem configuration, credentials | `agentos/docs/plugin-system.md` |
| Entities, CRUD controllers | `agentos/docs/entity-framework.md` |
| Neo4j persistence, soft delete | `agentos/docs/neo4j-persistence.md`, `agentos/docs/adr/` |
| Tests | `agentos/docs/testing.md` |
| Nx tasks | `agentos/docs/nx-tasks.md` |
| OpenAPI spec, generated client | `agentos/docs/openapi-and-client.md` |
| Running locally | `agentos/docs/running.md` |
| Case and namespace files | `agentos/docs/file-exchange.md` |
| Git repositories and worktrees per case | `agentos/docs/git-workspaces.md` |
| Delegation, sub-cases, multiple users | `agentos/docs/multi-user-and-delegation.md`, `agentos/docs/delegation-timeouts.md` |
| Personal overlays | `agentos/docs/user-level-overlays.md` |
| HTTP API integrations | `agentos/docs/http-api-integration.md` |
| Configuration agent (`AGENTOS_CONFIG`) | `agentos/docs/config-agent.md` |
| Usage and cost limits, metrics, scheduled prompts | `agentos/docs/usage-and-cost-limits.md`, `metrics.md`, `scheduled-prompt.md` |
| Product intent, vocabulary | `product/01-vision.md`, `product/02-domain-model.md`, `product/03-glossary.md` |
