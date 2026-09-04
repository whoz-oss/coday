# AgentOS — skills as first-class entities: state, decisions, and remaining work

> Implementation specification for PR #1277 (`feature/leo/issue-1275-filesystem-skills`).
> **Current status:** agent-facing capability, hardened filesystem source, and Delivery 1 domain / 
> Neo4j repository layer are implemented. Hybrid repository decorator, persistence wiring, CRUD
> service/API, platform-level shadowing resolution, and generated Skill contract remain.
>
> This document is canonical guide for completing implementation. Status reflects current working
> tree after filesystem restoration and verification (2840 backend tests, 0 failures). Update this
> document whenever implementation state or a settled decision changes.

---

## The goal in one sentence

Skills — reusable, on-demand instruction bundles an agent can consult — become **first-class AgentOS
entities with hybrid Neo4j-primary / filesystem-secondary storage**, advertised to agents as a compact
name-only catalogue and loaded through dedicated tools rather than through a general-purpose file
integration.

The reviewer's framing (Vincent, PR #1277):

> "In the end, skills should be entities in AgentOS managed in a similar way as integrations: hybrid
> storage with primary neo4j and secondary files with the de-facto composite repository pattern;
> loading through dedicated tools (no reliance on file integration existence), allowing skill usage
> monitoring."

Agent-facing runtime and filesystem source are built. Management/persistence plane is not built.

---

## Why this is one story, not two

The original plan was to ship filesystem-only discovery first (step 1) and promote skills to entities
later (step 2). That was rejected mid-flight, deliberately: shipping a storage shape that everyone
already knows will be replaced is delivering debt with a scheduled interest payment. The public
surfaces that would have to change — `SkillService`, the tools, the catalogue format — are exactly the
surfaces agents and prompts depend on.

The step-1 design was therefore built so that step 2 touches **zero agent-side code**. That property
is the whole point, and it is worth stating as an acceptance criterion: *if finishing step 2 requires
changing `AgentServiceImpl`, the step-1 design failed.*

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

### Path semantics — a repeated source of error

`Namespace.configPath` **already points at** `<projectRoot>/coday`. The discovery root is therefore:

```
<configPath>/skills/**/SKILL.md
```

Never `<configPath>/coday/skills` (doubled), and never `configPath.parent` — `configPath` is
arbitrarily positioned and its parent means nothing. The invariant: `configPath` contains `./agents`,
`./integrations`, `./prompts`, `./skills`.

`configPath.parent` arithmetic was written, reviewed, and removed once. It must not come back.

---

## Design decisions and their rationale

These are settled. They are recorded here because each one was reached by looking at codebase
precedent, and each one is the kind of thing a future reader would "simplify" back into the problem it
solves.

### 1. Capability grants are never on by default

`AgentConfig.skillSelectors` semantics:

| value | result |
|---|---|
| absent / `null` | **no skills** |
| `[]` | no skills |
| `["*"]` | all discovered skills |
| `["core/**"]` | folder prefix |
| `["product/spec-writing"]` | exact path under the skills root |
| `["spec-writing"]` | frontmatter name |

`null = all` was explicitly rejected in review:

> "it was the behavior on Coday typescript, handy in the beginning but just a plain design error in
> the long run."

This aligns skills with `subAgents` and removes the default-on upgrade problem — adding a skill to a
namespace must never silently change the behaviour of every existing agent. Since `null` and `[]`
collapse to the same meaning, `FilesystemAgentConfigRepository` normalises `skillSelectors` exactly as
it does `subAgents`:

```kotlin
?.filter { it.isNotBlank() }?.map { it.trim() }?.takeIf { it.isNotEmpty() }
```

**General rule for AgentOS: a capability grant is never on by default. Absent means none.**

### 2. Name-addressed, never path-addressed

The injected catalogue emits `- **<name>**: <description>` and nothing else — no filesystem paths.
Bodies and resources are retrieved through `readSkill(name)` and `readSkillResource(name, path)`.

This single decision collapses a cluster of problems at once:

- kills the dependency on the agent holding a `FILES` integration that happens to cover the skills
  path — an unrelated per-agent capability grant leaking into skill loading
- makes paths repository-internal, so `configPath.parent` disappears
- gives usage monitoring for free through normal tool tracing (the reviewer's third requirement)
- keeps bundled scripts unexposed — readable as text, never executed
- **is a precondition for DB-stored skills, which have no path at all**

### 3. `resourceRoot` follows the `AgentConfig.docs` precedent

`resourceRoot` is the absolute path of the directory holding `SKILL.md`, resolved by the repository at
parse time; null for non-filesystem skills.

This is exactly how `FilesystemAgentConfigRepository` resolves `AgentConfig.docs` entries to absolute
paths, after which `AgentDocumentResolver` consumes them storage-agnostically.

Rejected alternatives:

| alternative | why not |
|---|---|
| a `SkillResource` child entity | **zero precedent** — every composite in AgentOS is embedded on the entity (`Prompt.parameters`, `Prompt.translatedContent`, `IntegrationConfig.parameters`, `AgentConfig.externalMetadata`) |
| pointing at the namespace exchange area | conflates skill content with user-facing storage |

### 4. Storage asymmetry is accepted and documented, not engineered around

A Neo4j-stored skill has no `skillRelativePath` and no `resourceRoot`. Both fields are nullable and
filesystem-only.

The precedent is `FilesystemPromptRepository`, whose KDoc states outright that `agentConfigId` is
deliberately unsupported on the filesystem side, and narrows `findByScope` accordingly.

**The pattern: when a field cannot be supported on one storage side, don't support it, document why,
narrow the query — do not invent a mechanism to fake symmetry.**

Consequence that must be tested: a DB-stored skill can be matched by `*` or by its name, but **never**
by a folder-prefix or path selector.

### 5. Grant service without a `ToolPlugin`

`SkillToolGrantService` builds the tools directly. Granted iff the resolved catalogue is non-empty —
no platform default and no `integrations` key, because `skillSelectors` is already the opt-in.

A `SkillToolPlugin` was written first, mirroring `QueryUserToolPlugin`, and then **deleted**: its
`provideTools()` returned an empty list, because the real tools must close over the run's resolved
skill list, which no plugin-registry lookup can supply. It registered a phantom `SKILL` integration
type that nobody could declare.

**Lesson: follow a precedent only when its mechanism is actually used.** Structural mimicry of
`QueryUserToolPlugin` produced a registered no-op. Tools that must close over per-run state belong in
a grant service, not a plugin.

### 6. Reuse over reinvention

- caching goes through `FilesystemYamlCacheRegistry`, extended with an optional `filePredicate`
  (defaulted to the existing `.yaml`/`.yml` behaviour so existing callers are untouched). A
  hand-rolled `ConcurrentHashMap` TTL cache was written, flagged in review, and replaced.
- YAML parsing injects the shared `@Qualifier("yamlMapper")` bean, never an inline
  `ObjectMapper(YAMLFactory())`. Also flagged in review.
- the sensitive-file deny-list mirrors `AgentDocumentResolver.SENSITIVE_FILE_PATTERNS`

Operational bounds live as constants with a one-line comment, not as prose on domain properties:
`MAX_WALK_DEPTH = 4` (skills sit at depth 3 — `skills/domain/skill/SKILL.md`), `MAX_SKILL_FILE_BYTES`,
`MAX_SKILL_COUNT`, `MAX_SKILL_NAME_CHARS`, `MAX_SKILL_DESCRIPTION_CHARS`. **The body is bounded only by
file size and is never truncated by the name/description caps.**

---

## Current implementation state

Current implementation has two distinct planes. Agent-facing runtime is complete; entity management
plane remains.

### Implemented: agent-facing capability

- `AgentConfig.skillSelectors` is persisted, normalized, exposed through API, and present in generated
  OpenAPI. Null/empty means no skills.
- `AgentServiceImpl` resolves catalogue once per run through
  `skillService.findSkills(namespaceId, selectors)`.
- `SkillCatalogRenderer` injects names and descriptions only; filesystem paths never enter prompt.
- `SkillToolGrantService` grants `readSkill` and `readSkillResource` iff resolved catalogue is
  non-empty. No `SkillToolPlugin` exists and no `integrations` entry is required.
- `readSkill(name)` returns skill body. `readSkillResource(name, path)` reads filesystem resources
  under canonical `resourceRoot` with containment, sensitive-file, regular-file, and size guards.
- Skill tools join normal `dedupToolsByName` assembly, providing ordinary tool tracing/usage metrics.
- `AgentServiceImpl` is target-stable and must remain unchanged during remaining work.

### Implemented: hardened filesystem source

`FilesystemSkillRepository` currently remains standalone filesystem component (not yet hybrid
decorator), but discovery behavior is complete:

- shared `FilesystemYamlCacheRegistry`; no private cache
- shared qualified YAML mapper; no inline mapper
- exact `SKILL.md` predicate
- optional shared-cache `maxDepth`, default unbounded for backward compatibility
- skill traversal bounded at file depth 4; accepted skill-directory depths zero through three
- canonical containment checks for directory and direct-file symlink escapes
- `MAX_SKILL_FILE_BYTES`, `MAX_SKILL_COUNT = 500`, name and description caps
- deterministic path sorting, case-insensitive duplicate removal, earlier path wins
- count cap applies after dedup; duplicates do not consume budget
- body parsing strips one separator blank, preserves further/internal blanks and trailing-newline
  presence, adds no synthetic newline, and normalizes CRLF to LF
- selector filtering supports wildcard, folder prefix, exact relative path, and case-insensitive name
- selector filtering is internal to `SkillServiceImpl`, not part of public service contract

### Verified

Latest backend run: **2840 tests, 0 failures**. Focused cache/skill specs and broader
`agentos-service` build/test passed. Frontend was not touched or built.

### Implemented: Delivery 1 entity and Neo4j repository layer

- `Skill` implements `Entity` with `EntityMetadata`, nullable `namespaceId`, and nullable
  filesystem-only `skillRelativePath` / `resourceRoot`.
- `SkillNode` maps all audit/version metadata, scalar namespace id, and outgoing `BELONGS_TO`.
- `SkillRepository` exposes namespace, platform, and exact case-insensitive per-level name lookup.
- `SkillNodeNeo4jRepository` provides active namespace/platform/double-key queries.
- `Neo4jSkillRepository` implements save/read/soft-delete contracts, relationship synchronization,
  tombstoned uniqueness keys, and transactional multi-operation writes.
- Focused skill and `AgentServiceImplUnitSpec` tests pass. Full backend compile succeeded; known
  `ScheduledPromptBatchScenarioSpec` flake reproduced in full suite and passed in isolation.

### Not implemented: hybrid management plane

- `FilesystemSkillRepository` is not yet a `SkillRepository` decorator.
- Skill repository beans/package registration are absent from `Neo4jPersistenceConfiguration`.
- `SkillService` does not yet extend `EntityService`; final namespace-over-platform resolution and
  CRUD uniqueness/read-only rules are absent.
- `SkillDto`, `SkillController`, REST routes, Skill OpenAPI schema, and schema setup are absent.

Current service still resolves namespace configPath and depends on concrete filesystem repository.
`AgentServiceImpl` remains unchanged, as required.

---

## Remaining work

### Delivery 1 — entity + node + repository (implemented, pending checkpoint review)

Mirror `IntegrationConfig` exactly. Read these first; they *are* the specification:

`IntegrationConfig.kt` · `IntegrationConfigNode.kt` · `IntegrationConfigNodeNeo4jRepository.kt` ·
`IntegrationConfigRepository.kt` · `Neo4jIntegrationConfigRepository.kt`

```kotlin
data class Skill(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID?,                 // null = platform-level
    val name: String,
    val description: String,
    val body: String,
    val skillRelativePath: String? = null,  // filesystem-only
    val resourceRoot: String? = null,       // filesystem-only, absolute
) : Entity
```

```kotlin
interface SkillRepository : EntityRepository<Skill, UUID> {
    fun findByNamespaceId(namespaceId: UUID): List<Skill>
    fun findByNameInNamespace(namespaceId: UUID?, name: String): Skill?
}
```

- `findByParent(parentId)` implemented as `findByNamespaceId(parentId)` by convention, as
  `IntegrationConfigRepository` does.
- `SkillNode`: `@Node("Skill")`, `@Id val id: String`, **both** a scalar `namespaceId: String?` for
  scalar queries **and** a `@Relationship(type = "BELONGS_TO", direction = OUTGOING)` to
  `NamespaceNode` for transitive-permission Cypher. Keep both in sync in `save`, exactly as
  `Neo4jAgentConfigRepository.save` does.
- All `EntityMetadata` fields: `@Version`, `@CreatedDate`, `@CreatedBy`, `@LastModifiedDate`,
  `@LastModifiedBy`, `removed`.
- `@Transactional` on `save` / `deleteByParent` where the impl does more than one operation
  (node + edge), mirroring `Neo4jPromptRepository`.
- Register `io.whozoss.agentos.skill` in `@EnableNeo4jRepositories(basePackages = [...])` in
  `Neo4jPersistenceConfiguration`.

**Platform-level skills (`namespaceId == null`): in scope.** A shared core skill library is the obvious
use case, and retrofitting it later means touching every query. Replicate `AgentConfig`'s shadowing
rule — a namespace-scoped skill shadows a platform skill of the same name, uniqueness enforced per
level (see `AgentServiceImpl.resolveWithShadowing`).

Verify green before continuing.

### Delivery 2 — filesystem repository becomes a decorator

The main restructuring. `FilesystemSkillRepository` currently is a standalone `@Component` with
`findAll(configPath: String)`. It becomes:

```kotlin
class FilesystemSkillRepository(
    private val delegate: SkillRepository,
    private val namespaceRepository: NamespaceRepository,
    private val yamlMapper: ObjectMapper,
    ttl: Duration = Duration.ofMinutes(5),
) : SkillRepository by delegate
```

- Drop `@Component` — it becomes a constructed bean, like both siblings.
- Override reads to merge persisted + filesystem, **persisted wins on name collision**
  (case-insensitive), via the `excludeNames` idiom.
- Forward all writes untouched. **The filesystem is never written.**
- Stable UUID: `UUID.nameUUIDFromBytes("filesystem-skill:<name>".toByteArray(Charsets.UTF_8))`,
  matching the sibling convention of deriving from name alone.
- `namespaceId` is null at parse time, overwritten by the caller via `.copy(namespaceId = ...)`,
  exactly as `filesystemConfigs()` does.
- Directory is `Path.of(configPath, "skills")`. The `FilesystemYamlCacheRegistry` parser receives
  `(directory, file)` where `directory` **is** `<configPath>/skills`. **Do not reintroduce
  `configPath.parent`.**
- `findByIds` must resolve filesystem-derived ids. `FilesystemAgentConfigRepository.findByIds` has a
  non-obvious implementation — when the delegate cannot satisfy an id it scans all namespaces with a
  configPath. Mirror it; existing agent-config tests show the expected semantics.

**Every step-1 behaviour and its test must be preserved**: canonical-path/symlink boundary rejection,
deterministic path ordering, duplicate-name dedup (first-by-path, case-insensitive),
malformed-frontmatter skip with WARN, `MAX_SKILL_FILE_BYTES` guard, `MAX_SKILL_COUNT` truncation with a
single WARN, `MAX_WALK_DEPTH` guard, whitespace collapsing, name/description truncation with ellipsis,
body never truncated by the name/description caps.

### Delivery 3 — bean wiring

In `Neo4jPersistenceConfiguration`, use the **two-bean idiom**:

```kotlin
@Bean
fun neo4jSkillRepositoryDelegate(...): Neo4jSkillRepository = Neo4jSkillRepository(...)

@Bean
@Primary
fun neo4jSkillRepository(
    neo4jSkillRepositoryDelegate: Neo4jSkillRepository,
    namespaceRepository: NamespaceRepository,
    @Qualifier("yamlMapper") yamlMapper: ObjectMapper,
): SkillRepository = FilesystemSkillRepository(...)
```

The inner delegate **must** be an explicit `@Bean`. Constructing it inline inside the outer factory
method leaves it unmanaged by Spring, the AOP proxy is never applied, and `@Transactional` silently
stops working. Read the KDoc on `neo4jIntegrationConfigRepositoryDelegate` and
`neo4jPromptRepositoryDelegate` — both record this trap.

### Delivery 4 — service moves onto the repository

`SkillServiceImpl` stops depending on `FilesystemSkillRepository` + `NamespaceService` and depends on
`SkillRepository`, calling `findByNamespaceId(namespaceId)`.

**This is the point of the whole exercise**: the duplicate cache and the configPath resolution existed
only because `SkillService` sat outside the repository layer. Both disappear from the service.

- Keep the two suspend signatures **exactly** as they are.
- Repository reads are synchronous; keep `withContext(Dispatchers.IO)` at the service level.
- Have `SkillService` extend `EntityService<Skill, UUID>` for controller CRUD, as
  `IntegrationConfigService` does, keeping the two skill-specific suspend methods as additions.
- Uniqueness validation on create/update via `findByNameInNamespace`, mirroring
  `IntegrationConfigServiceImpl`'s use of `findByTriple`.

**Acceptance criterion: `AgentServiceImpl` compiles untouched.**

### Delivery 5 — controller and DTO

Mirror `IntegrationConfigController`.

- `SkillDto` in `agentos-sdk` under `io/whozoss/agentos/sdk/api/skill/`: `@Schema(name = "Skill")`,
  `@JsonIgnoreProperties(ignoreUnknown = true)`, `@JsonInclude(NON_NULL)`, `@field:NotBlank` on
  required strings, audit fields.
- `@PreAuthorize` guards must satisfy `PreAuthorizeLabelConsistencySpec`. **Read that spec; do not
  modify it** — it is being split into its own PR.
- **Filesystem-backed skills are read-only through the API.** Update/delete must fail cleanly. Follow
  how the existing controllers handle this for filesystem agents and prompts, with the same exception
  type.
- Regenerate the OpenAPI spec (there is already a `chore:` commit doing this for `skillSelectors`).

### Delivery 6 — schema and generated contract

If siblings declare Neo4j indexes/constraints in `Neo4jSchemaInitializer`, add equivalent for `Skill`.
Follow `IntegrationConfig` exactly — **if it has none, add none.** Regenerate OpenAPI after controller
and DTO exist; generated contract must include Skill CRUD schema in addition to existing
`AgentConfig.skillSelectors`.

---

## Implementation sequence and verification gates

Complete deliveries in order. Each delivery must compile and pass focused tests before next begins.
Do not combine all remaining work into one unverified change.

1. Delivery 1: domain/node/repository mapping tests green.
2. Delivery 2: hybrid repository tests green, including every existing filesystem behavior.
3. Delivery 3: Spring context/repository wiring green.
4. Delivery 4: service selector, uniqueness, and shadowing tests green; `AgentServiceImpl` unchanged.
5. Delivery 5: controller integration/security/read-only tests green.
6. Delivery 6: schema/OpenAPI generated and full backend verification green.

No commit without Leo's explicit approval after diff review.

## Testing requirements

- `FilesystemSkillRepositoryUnitSpec` — restructure for the decorator shape, mirroring
  `FilesystemAgentConfigRepositoryUnitSpec`. Cover: delegation when the namespace has no configPath,
  filesystem-only results, merge ordering (persisted first, then filesystem sorted), persisted wins on
  collision case-insensitively, writes forwarded, stable UUID across instances, `findByIds` resolving
  a filesystem-derived id, **plus every preserved behaviour listed in Delivery 2**.
- `SkillServiceImplUnitSpec` — rework to mock `SkillRepository`. Keep every selector case. **Add: a
  DB-stored skill (null `skillRelativePath`) matches `*` and its name but NOT a folder-prefix or path
  selector.**
- `SkillReadToolUnitSpec` — should need no change beyond construction. Note `ToolExecutionResult`
  exposes `success`, **not** `isSuccess`.
- New controller integration spec mirroring `AgentConfigControllerIntegrationSpec`: CRUD happy paths,
  validation rejection, name-collision rejection, permission denial, read-only rejection for a
  filesystem-backed skill.
- `AgentServiceImplUnitSpec` — adapt **only** if the compiler forces it. If it forces changes, that is
  a signal the layering broke.
- Kotest `StringSpec` + MockK, matching surrounding style.

```bash
pnpm nx run-many -t build
pnpm nx affected -t lint test
```

---

## Traps encountered — do not rediscover these

### Kotlin nests block comments

**Kotlin block comments nest.** A literal slash-star inside a KDoc — trivially easy when documenting
glob patterns — opens a nested comment that the KDoc's own closing delimiter then closes, leaving the
outer comment open and swallowing the rest of the file.

The compiler reports "unclosed comment" at EOF plus a cascade of errors on perfectly valid lines:
`'metadata' overrides nothing`, `Primary constructor of data class must only have property parameters`,
`No parameter with name 'docs' found`. **Not one of these points at the real fault.**

This cost two confidently wrong diagnoses in a single session: "the file tool is not writing to disk"
and "the data class constructor is malformed". Both false; the fault was a comment.

**Convention: spell path patterns in words inside KDoc** — "path ending with slash-star", "folder
prefix such as core followed by slash-star-star". `AgentDocumentResolver` already does this, which is
precisely why it compiles. Backticks give no protection; the lexer does not read Markdown.

Heuristic: when Kotlin reports "unclosed comment" *together with* structural errors in a file that
looks valid, grep the comments for a slash-star sequence before debugging the class. A cascade whose
reported locations are all valid code usually means the **lexer** state is wrong, not the code.

### `ScheduledPromptBatchScenarioSpec` is flaky

`ClassCastException: Entity$Subclass21 cannot be cast to Case` in `ScheduledPromptExecutor.awaitLaunch`
— a MockK subclass-mock artifact with order-dependent behaviour. Fails on this branch **and** on a
clean tree; a different test in the suite fails on different runs. Unrelated to skills. Do not let it
block, do not claim skill work fixed it, and do not record it as resolved. It deserves its own issue.

### Delegation of entangled work

The step-2 regression came from delegating a large, precise brief to a fresh-context agent. A fresh
agent reading the current files cannot distinguish "reviewed decision" from "incidental state" and
optimises toward what it sees — so it reverted review outcomes it had no way to recognise as
deliberate.

Mitigations, in order of effectiveness:

1. **Commit reviewed state before delegating.** Uncommitted work has no defence.
2. Slice small and verify green between slices.
3. Prefer resuming an existing sub-thread over opening a fresh one for entangled work.

---

## Key files

### Skill package (`agentos-service/.../skill/`)

`Skill.kt` · `SkillService.kt` · `SkillServiceImpl.kt` · `FilesystemSkillRepository.kt` ·
`SkillCatalogRenderer.kt` · `SkillReadTool.kt` (holds both tools) · `SkillToolGrantService.kt`

To be created: `SkillNode.kt` · `SkillNodeNeo4jRepository.kt` · `SkillRepository.kt` ·
`Neo4jSkillRepository.kt` · `SkillController.kt` · `SkillDto.kt` (in `agentos-sdk`)

### Pattern references — read before writing

- `integrationConfig/` — the full entity pattern, end to end
- `prompt/FilesystemPromptRepository.kt` — documented storage asymmetry (the `agentConfigId` KDoc)
- `agentConfig/FilesystemAgentConfigRepository.kt` — `findByIds` across namespaces, `docs` path
  resolution, `subAgents` normalisation
- `agentConfig/AgentDocumentResolver.kt` — sensitive-file deny-list, and the words-not-symbols KDoc
  convention
- `config/Neo4jPersistenceConfiguration.kt` — the two-bean delegate idiom
- `plugin/filesystem/FilesystemYamlCache.kt` — the registry and its `filePredicate`
- `agent/AgentServiceImpl.kt` — `resolveAgentDefinition`, shadowing, `dedupToolsByName`
- `queryUser/QueryUserToolGrantService.kt` — the grant-service pattern (**but not** its plugin)
- `permissions/` + `PreAuthorizeLabelConsistencySpec` — controller guard rules

---

## Definition of done

1. `Skill` implements `Entity` with `EntityMetadata` + `namespaceId`.
2. `SkillNode`, `SkillNodeNeo4jRepository`, `SkillRepository`, `Neo4jSkillRepository` follow the
   sibling pattern; `io.whozoss.agentos.skill` registered in `@EnableNeo4jRepositories`.
3. `FilesystemSkillRepository` is a decorator (`: SkillRepository by delegate`), writes forwarded,
   persisted wins on collision.
4. Beans wired with the explicit-inner-delegate + `@Primary`-outer-decorator idiom.
5. `SkillServiceImpl` depends on `SkillRepository`; no configPath resolution and no second cache in
   the service layer.
6. The two `SkillService` suspend signatures unchanged; **`AgentServiceImpl` unchanged**.
7. `SkillController` + `SkillDto` with `@PreAuthorize` satisfying `PreAuthorizeLabelConsistencySpec`
   (unmodified); filesystem skills read-only via API.
8. Storage asymmetry for `skillRelativePath` / `resourceRoot` documented in KDoc and covered by a test.
9. Every step-1 behaviour and its test preserved.
10. Platform-level skill shadowing implemented and tested.
11. OpenAPI spec regenerated.
12. Build + lint + test green, modulo the known flaky spec.
