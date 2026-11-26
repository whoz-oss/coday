---
name: agentos-backend
description: AgentOS backend work in agentos/ (Kotlin, Spring Boot 3, Spring AI, Spring Data Neo4j, PF4J plugins, Gradle composite build, OpenAPI generation). Read before changing any Kotlin or AgentOS service code.
---

# AgentOS backend

Use this skill for any change under `agentos/`: Kotlin, Spring Boot 3, Spring AI, Spring Data Neo4j, the PF4J
plugin system and the Gradle composite build. Do not use it to change `apps/` or `libs/`: browser-side code
belongs to the `angular-frontend` skill.

## Read before coding

Open with `FILES` the documents that match the task, and follow them over this summary:

- `agentos/README.md` and `agentos/agentos-sdk/README.md`: modules, build, SDK usage
- `agentos/docs/architecture.md`: module structure and package maps (SDK vs service)
- `agentos/docs/code-style.md`: Kotlin conventions (`when` over `if`/`else if`, no early return mid-method)
- `agentos/docs/testing.md`: Kotest/MockK conventions and file naming (`*UnitSpec`, `*IntegrationSpec`)
- `agentos/docs/entity-framework.md`: entities, repositories, services, controllers, resources
- `agentos/docs/neo4j-persistence.md`: nodes, relationships, `@Query` conventions, persistence tests
- `agentos/docs/plugin-system.md`: PF4J plugins, filesystem configuration, credentials
- `agentos/docs/openapi-and-client.md`: spec and TypeScript client generation
- Other topics in `agentos/docs/` (file exchange, config agent, git workspaces, usage and cost limits, scheduled
  prompts, delegation) when the task touches them

## Boundaries and conventions

- Two Gradle modules in a composite build: `agentos-sdk` (plugin contract, domain models, HTTP API DTOs and `*Api`
  interfaces, no Spring) and `agentos-service` (Spring Boot application). Respect the SDK/service split: plugins
  depend on the SDK only.
- Domain entities are never exposed over HTTP: each controller maps to a resource/DTO, with Bean Validation on the
  DTO. Routing annotations live on the controller, never on the SDK interface.
- Persistence: one `*Node`, one Spring Data repository and one hand-written repository per entity, registered in
  `Neo4jPersistenceConfiguration`. Soft delete only, active-only filtering in every query.
- Cypher `@Query` strings use the Kotlin raw delimiter described in `neo4j-persistence.md`.
- KDoc: never write a slash followed by a star inside a KDoc (for example in a glob such as `agents/*`), Kotlin
  nests comments and the file stops compiling. Spell the glob differently or put it in backticks without it.
- Plugin tools are wrapped by implementing `ToolCallback` directly, never `MethodToolCallback`.

## OpenAPI and generated client

- The spec `agentos/openapi/agentos-openapi.yaml` is generated from the controllers and never edited by hand; CI
  fails if it drifts. After changing an endpoint or a DTO, regenerate with
  `pnpm nx run agentos-service:regenerate` and commit the spec together with the Kotlin change.
- The TypeScript client in `libs/agentos-api-client/src/lib/` is generated: never edit it, and mention that a
  frontend adaptation may be needed instead of doing it yourself.

## Tests

Follow the existing patterns only (Kotest `StringSpec`, MockK, unit specs without Spring context, integration specs
with the `test` profile). If a test cannot be made to pass after reasonable effort, say so instead of weakening it.

## Verification

Use the `PROJECT_SCRIPTS` tools (`gradle`, `nx`) when available; otherwise the commands below from the repository root:

```bash
cd agentos && ./gradlew :agentos-service:test --tests <TestClass>
cd agentos && ./gradlew :agentos-sdk:test
pnpm nx test agentos-service
pnpm nx run agentos-service:check-openapi-spec
```

Report exactly what you ran and what you could not run (Java 25 is required for the JVM services).
