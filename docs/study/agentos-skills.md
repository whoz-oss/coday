# AgentOS — skills as first-class entities: state, decisions, and remaining work

> Implementation specification for PR #1277 (`feature/leo/issue-1275-filesystem-skills`).
> **Current status:** Deliveries 1 through 6 and hardening pass are fully implemented and verified.
> Skills are first-class AgentOS entities backed by hybrid Neo4j-primary / filesystem-secondary
> storage, registered in the schema initializer, exposed through REST/OpenAPI, and resolved
> at runtime by agents on demand.
>
> This document is the canonical reference reflecting the settled architecture and implementation.

---

## The goal in one sentence

Skills — reusable, on-demand instruction bundles an agent can consult — are **first-class AgentOS
entities with hybrid Neo4j-primary / filesystem-secondary storage**, advertised to agents as a compact
name-only catalogue and loaded through dedicated tools rather than through a general-purpose file
integration.

The reviewer's framing (Vincent, PR #1277):

> "In the end, skills should be entities in AgentOS managed in a similar way as integrations: hybrid
> storage with primary neo4j and secondary files with the de-facto composite repository pattern;
> loading through dedicated tools (no reliance on file integration existence), allowing skill usage
> monitoring."

---

## Storage format

Claude-compatible filesystem skills. Each skill is a directory containing `SKILL.md`:

```
<namespace.configPath>/skills/
  core/branch-creation/SKILL.md
  product/spec-writing/SKILL.md
```

`SKILL.md` is YAML frontmatter (`name`, `description`, both required and non-blank) followed by a
markdown body. Adjacent files (`references/`, `assets/`, `scripts/`) provide progressive disclosure.

Project convention is `coday/skills/`, **not** `.coday/skills/`.

### Path semantics

`Namespace.configPath` **already points at** `<projectRoot>/coday`. The discovery root is therefore:

```
<configPath>/skills/**/SKILL.md
```

Never `<configPath>/coday/skills` (doubled), and never `configPath.parent` — `configPath` is
arbitrarily positioned and its parent means nothing. The invariant: `configPath` contains `./agents`,
`./integrations`, `./prompts`, `./skills`.

---

## Design decisions and their rationale

### 1. Capability grants are never on by default

`AgentConfig.skillSelectors` semantics:

| value | result |
|---|---|
| absent / `null` | **no skills** |
| `[]` | no skills |
| `["*"]` | all discovered skills |
| `["core/**"]` | recursive folder prefix (core and all subtrees) |
| `["core/*"]` | direct child folder prefix (direct children only) |
| `["product/spec-writing"]` | exact path under the skills root |
| `["spec-writing"]` | frontmatter name |

`null = all` was explicitly rejected in review. This aligns skills with `subAgents` and removes
the default-on upgrade problem — adding a skill to a namespace never silently changes the behaviour
of existing agents. Normalization trims strings and collapses empty lists to null across both
`AgentConfigController` and `FilesystemAgentConfigRepository`.

### 2. Name-addressed, never path-addressed

The injected catalogue emits `- **<name>**: <description>` and nothing else — no filesystem paths.
Bodies and resources are retrieved through `readSkill(name)` and `readSkillResource(name, path)`.

This decision:
- Removes dependence on an agent holding a `FILES` integration covering the skills path
- Keeps paths repository-internal
- Gives usage monitoring for free through normal tool tracing
- Keeps bundled scripts unexposed — readable as text, never executed
- Supports DB-stored skills, which have no path at all

### 3. `resourceRoot` follows the `AgentConfig.docs` precedent

`resourceRoot` is the absolute path of the directory holding `SKILL.md`, resolved by the repository at
parse time; null for DB-stored skills.

### 4. Storage asymmetry is accepted and documented

A Neo4j-stored skill has no `skillRelativePath` and no `resourceRoot`. Both fields are nullable and
filesystem-only.

A DB-stored skill matches `*` or its exact name, but **never** a folder-prefix (`core/**`, `core/*`)
or relative-path selector.

### 5. Grant service without a `ToolPlugin`

`SkillToolGrantService` builds the tools directly. Granted iff the resolved catalogue is non-empty —
no platform default and no `integrations` key, because `skillSelectors` is already the opt-in. Defensively
returns empty list on empty skill lists.

### 6. Reuse over reinvention

- Caching goes through `FilesystemYamlCacheRegistry` with exact `SKILL.md` predicate.
- YAML parsing injects the shared `@Qualifier("yamlMapper")` bean.
- Sensitive-file deny-list mirrors `AgentDocumentResolver.SENSITIVE_FILE_PATTERNS`.
- Bounded traversal at file depth 4 (`MAX_WALK_DEPTH = 4`), single-stream bounded read size guard
  (`MAX_RESOURCE_BYTES = 1 MiB`), count cap (`MAX_SKILL_COUNT = 500`), name and description caps with ellipsis.

---

## Completed Architecture & Deliveries (1–6)

### Delivery 1: Domain & Neo4j Persistence
- `Skill` domain entity (`EntityMetadata`, `namespaceId`, `name`, `description`, `body`, `skillRelativePath?`, `resourceRoot?`).
- `SkillNode` Spring Data Neo4j mapping (`@Node("Skill")`, `id`, `doubleKey`, `namespaceId`, scalar + `BELONGS_TO` edge to `NamespaceNode`).
- `SkillNodeNeo4jRepository` with `@Query` active-filtering queries (`findActiveByNamespaceId`, `findActivePlatform`, `findActiveByDoubleKey`).
- `Neo4jSkillRepository` implementing `SkillRepository` with transactional writes and soft deletes using tombstone doubleKeys.

### Delivery 2: Hybrid Decorator Pattern
- `FilesystemSkillRepository` converted from standalone `@Component` to decorator:
  `class FilesystemSkillRepository(...) : SkillRepository by delegate`
- Merges persisted (delegate) and filesystem results, persisted winning case-insensitively on name collisions.
- Filesystem skills assigned stable UUIDs `UUID.nameUUIDFromBytes("filesystem-skill:<name>")`.
- `findByIds` scans missing filesystem IDs across namespaces with `configPath` without duplicates.
- All writes (`save`, `delete`, `deleteByParent`) forwarded to delegate; filesystem is never written.

### Delivery 3: Spring Bean Wiring
- `Neo4jPersistenceConfiguration` wires the two-bean delegate idiom:
  - `neo4jSkillRepositoryDelegate`: inner managed bean preserving Spring AOP `@Transactional` proxying.
  - `neo4jSkillRepository`: `@Primary` bean wrapping delegate in `FilesystemSkillRepository`.
- `io.whozoss.agentos.skill` registered in `@EnableNeo4jRepositories`.

### Delivery 4: EntityService CRUD & Runtime Resolution
- `SkillService` extends `EntityService<Skill, UUID>` and provides suspend runtime discovery (`findSkills`, `findSkillByName`).
- `SkillServiceImpl` enforces case-insensitive name uniqueness per level via `findByNameInNamespace`.
- `loadEffectiveSkills` merges namespace skills + platform skills (shadowed by namespace on name collision).
- `findSkillByName` checks namespace first, then platform fallback.
- Filesystem skills are read-only: mutating or deleting them via service throws `400 Bad Request`.
- `AgentServiceImpl` remained completely untouched.

### Delivery 5: REST API & DTO
- `SkillDto` in `agentos-sdk` under `io.whozoss.agentos.sdk.api.skill`: schema `Skill`, Bean Validation (`@NotBlank`), audit timestamps.
- `SkillApi` SDK contract.
- `SkillController` at `/api/skills` implementing `SkillApi`:
  - `GET /{id}`: `@PreAuthorize("hasPermission(#id, 'Skill', 'READ')")` + `@HideOnAccessDenied`
  - `POST /by-ids`: `@PreAuthorize("isAuthenticated()")`
  - `GET /by-parentId/{parentId}`: `@PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")`
  - `GET /platform`: `@PreAuthorize("isAuthenticated()")`
  - `POST /`: `@PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")`
  - `PUT /{id}`: `@PreAuthorize("hasPermission(#id, 'Skill', 'WRITE')")`
  - `DELETE /{id}`: `@PreAuthorize("hasPermission(#id, 'Skill', 'DELETE')")`
- `EntityType.SKILL("Skill")` registered; passes `PreAuthorizeLabelConsistencySpec`.

### Delivery 6: Schema Constraints, OpenAPI Contract & Hardening
- `Neo4jSchemaInitializer` ensures `skill_id_unique` and `skill_double_key_unique` constraints.
- OpenAPI specification regenerated: `/api/skills` endpoints and `Skill` schema published in `openapi/agentos-openapi.yaml`.
- Hardened `SkillReadResourceTool` with single-stream bounded memory read preventing TOCTOU.
- Verified single-level vs recursive selector filtering in `SkillServiceImpl`.
- Verified `AgentConfig` skillSelectors normalization across controller and repository.

---

## Traps & Testing Notes

### Kotlin Block Comments Nest
Never use literal slash-star in KDoc comments; spell out patterns in words (e.g. "path ending with slash-star").

### Flaky Spec
`ScheduledPromptBatchScenarioSpec` exhibits occasional MockK subclass-mock artifact flake under parallel runs. Unrelated to skills.

---

## Definition of Done Checklist

| Criteria | Status |
|---|---|
| 1. `Skill` implements `Entity` with `EntityMetadata` + `namespaceId` | ✅ Complete |
| 2. `SkillNode`, `SkillNodeNeo4jRepository`, `SkillRepository`, `Neo4jSkillRepository` implemented | ✅ Complete |
| 3. `FilesystemSkillRepository` is a decorator (`: SkillRepository by delegate`), writes forwarded, persisted wins on collision | ✅ Complete |
| 4. Beans wired with explicit delegate + `@Primary` decorator in `Neo4jPersistenceConfiguration` | ✅ Complete |
| 5. `SkillServiceImpl` depends on `SkillRepository`; extends `EntityService<Skill, UUID>` | ✅ Complete |
| 6. Runtime suspend signatures intact; `AgentServiceImpl` unchanged | ✅ Complete |
| 7. `SkillController` + `SkillDto` with `@PreAuthorize` satisfying `PreAuthorizeLabelConsistencySpec`; filesystem skills read-only via API | ✅ Complete |
| 8. Storage asymmetry documented and tested | ✅ Complete |
| 9. Step-1 parsing, boundary, bounds, and caching behavior preserved | ✅ Complete |
| 10. Platform-level skill shadowing implemented and tested | ✅ Complete |
| 11. Schema constraints ensured in `Neo4jSchemaInitializer` | ✅ Complete |
| 12. OpenAPI spec regenerated | ✅ Complete |
| 13. Full backend build + lint + test green | ✅ Complete |
