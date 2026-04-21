# Neo4j Persistence

## Persistence Modes

AgentOS supports three persistence modes, selected via `agentos.persistence.mode`
(or the `AGENTOS_PERSISTENCE_MODE` environment variable):

| Mode | When to use | Neo4j required |
|---|---|---|
| `embedded-neo4j` | Local single-user deployment. Default. | No — engine starts in-process |
| `neo4j` | Multi-user server deployment | Yes — standalone server |
| `in-memory` | Ephemeral / testing only | No |

The default mode is `embedded-neo4j`. The `in-memory` mode is deprecated and will
be removed in a future release.

### embedded-neo4j

A Neo4j Community Edition engine starts in-process on boot via
`EmbeddedNeo4jConfiguration`. The database files live under
`<agentos.persistence.data-dir>/neo4j/` (default: `data/neo4j/`).

The engine exposes a Bolt connector on `localhost:7688` (configurable via
`agentos.persistence.embedded-bolt-port`). Port 7688 is chosen to avoid
conflicting with a standalone Neo4j instance on the standard port 7687. Set
the port to `0` for an OS-assigned random port.

Spring Data Neo4j connects exclusively over Bolt, so `spring.neo4j.uri` must
match the configured port even when using the embedded engine. The
`application-embedded-neo4j.yml` profile file sets this automatically.

You can inspect the embedded database at runtime with Neo4j Desktop or
`cypher-shell` by pointing them at `bolt://localhost:7688`.

### neo4j (standalone server)

Activate with `--spring.profiles.active=neo4j` or by setting
`AGENTOS_PERSISTENCE_MODE=neo4j`. Configure the connection via:

```yaml
spring:
  neo4j:
    uri: ${NEO4J_URI:bolt://localhost:7687}
    authentication:
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:agentos-dev}
```

For local development, start a server with `docker compose up neo4j`.

## Spring Configuration Wiring

`Neo4jPersistenceConfiguration` is active for both `neo4j` and `embedded-neo4j`
modes (via `@ConditionalOnExpression`). It:

1. Enables `@EnableNeo4jRepositories` scanning for the `io.whozoss.agentos.persistence.neo4j` package.
2. Declares `@Bean` methods that wrap each Spring Data `Neo4jRepository` in a
   domain-facing `EntityRepository` implementation.

Each `Neo4jXxxRepository` receives the raw Spring Data repository and a mapper
(or `ObjectMapper` for JSON fields) as constructor arguments. This keeps the
domain layer free of Spring Data annotations.

## Node / Domain Mapping Pattern

Every domain entity has a corresponding **node class** (e.g. `CaseNode`,
`NamespaceNode`, `UserNode`). The node class is the SDN entity — it carries
`@Node` and `@Id` and is the type stored in and retrieved from the graph. The
domain class never touches SDN annotations.

### Flat properties convention

All node classes keep their properties flat. Nested objects (like `EntityMetadata`)
are inlined as individual scalar properties on the node:

```kotlin
@Node("Case")
data class CaseNode(
    @Id val id: String,
    val namespaceId: String,   // scalar — always written, read by toDomain()
    val status: String,
    val title: String,
    val created: Instant,
    val createdBy: String?,
    val modified: Instant,
    val modifiedBy: String?,
    val removed: Boolean?,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,  // populated on read via RETURN c, r, ns
)
```

This avoids SDN Community Edition's limited support for embedded value types
and keeps Cypher queries simple.

### `@Id` type is `String`

All node `@Id` fields are `String` (UUID serialised via `.toString()`). Domain
entities use `UUID`. Conversion happens in `toDomain()` / `fromDomain()`.

### `removed` is `Boolean?`

The soft-delete flag is stored as a nullable `Boolean?`. A missing property
(`null`) means not removed; the Cypher queries therefore filter with
`WHERE e.removed IS NULL OR e.removed = false` rather than `WHERE e.removed = false`.
When marking an entity as removed, the flag is set to `true`; when writing a
non-removed entity, `removed` is written as `null` (`removed.takeIf { it }`).
This avoids polluting the graph with `removed: false` on every node.

### Parent relationships as graph edges

All parent links are stored as real Neo4j relationships:

```
(:Case)-[:BELONGS_TO]->(:Namespace)
(:IntegrationConfig)-[:BELONGS_TO]->(:Namespace)
(:CaseEvent)-[:BELONGS_TO]->(:Case)
```

The `@Relationship` field on each node class is declared as a nullable `var` so
SDN can call the primary constructor before injecting the field on reads:

```kotlin
@Relationship(type = "BELONGS_TO", direction = OUTGOING)
var namespace: NamespaceNode? = null
```

#### Writing the edge: always use a dedicated link query

**Never** write the relationship by setting the `@Relationship` field to a stub
node in `fromDomain`. SDN does not MERGE the related node by `@Id` — it saves
all properties of the stub, overwriting the real node's data with empty values
(e.g. `name = ""`, `status = ""`).

Instead, save the owning node first (with no `@Relationship` field set), then
call a dedicated `@Query` that MATCHes both existing nodes and MERGEs only the
edge between them:

```kotlin
// Repository interface
@Query("""MATCH (c:Case {id: $caseId})
          MATCH (ns:Namespace {id: $namespaceId})
          MERGE (c)-[:BELONGS_TO]->(ns)""")
fun linkCaseToNamespace(caseId: String, namespaceId: String)

// Repository implementation
override fun save(entity: Case): Case =
    caseNodeNeo4jRepository
        .save(CaseNode.fromDomain(entity))
        .also { caseNodeNeo4jRepository.linkCaseToNamespace(it.id, entity.namespaceId.toString()) }
        .toDomain()
```

The same pattern applies to `IntegrationConfig → Namespace` (`linkConfigToNamespace`)
and `CaseEvent → Case` (`linkEventToCase`).

#### Reading the relationship: return node + relationship + related node

SDN 7 only injects `@Relationship` fields when the query result contains the
relationship instance and the related node explicitly. The correct form:

```cypher
MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
WHERE ns.id = $namespaceId AND (c.removed IS NULL OR c.removed = false)
RETURN c, r, ns ORDER BY c.created ASC
```

For `CaseEvent`, the `linkEventToCase` call runs after the node save, so the
edge may not exist yet when `findActiveByCaseId` is called. Use `OPTIONAL MATCH`
so the query still returns results when the edge is absent:

```cypher
MATCH (e:CaseEvent)
WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
OPTIONAL MATCH (e)-[r:BELONGS_TO]->(c:Case)
RETURN e, r, c ORDER BY e.timestamp ASC, e.id ASC
```

#### Scalar denormalisation for toDomain()

The node returned by `save()` does not have its `@Relationship` field injected
— that only happens for custom queries returning `node, rel, related`. `toDomain()`
therefore reads the parent id from a **scalar property** on the node:

```kotlin
val namespaceId: String  // written by fromDomain alongside the graph edge

fun toDomain() = Case(
    namespaceId = UUID.fromString(namespaceId),  // scalar — always present
    ...
)
```

The scalar and the graph edge are always written together on save. The scalar is
the reliable source for `toDomain()`; the graph edge is the reliable source for
`findByParent` queries and graph traversal.

### `toDomain()` / `fromDomain()`

Simple node classes (no complex serialisation) embed conversion directly as
`toDomain()` and `companion object { fun fromDomain(...) }` on the node class
itself. The `CaseEventNode` hierarchy uses a dedicated `CaseEventNodeMapper`
because the sealed hierarchy requires a central dispatch point.

`withRemoved` in `CaseEventNodeMapper` reconstructs the subtype node and
copies the `case` relationship field so the `BELONGS_TO` edge is preserved
through soft-delete operations.

## CaseEvent: Dual-Label Strategy

`CaseEvent` is a sealed interface with 14 concrete subtypes. Each subtype is
stored as a graph node with **two labels**: the primary `:CaseEvent` label and
a secondary subtype label (e.g. `:MessageEvent`, `:AgentFinishedEvent`).

This is expressed in the class hierarchy:

```kotlin
@Node("CaseEvent")           // primary label on the abstract base
sealed class CaseEventNode(...)

@Node("MessageEvent")        // secondary label on each concrete subclass
class MessageEventNode(...) : CaseEventNode(...)
```

SDN uses the secondary label to instantiate the correct `CaseEventNode` subclass
on read. The primary `:CaseEvent` label allows `findActiveByCaseId` to query
across all subtypes with a single Cypher pattern:

```cypher
MATCH (e:CaseEvent)
WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
RETURN e ORDER BY e.timestamp ASC, e.id ASC
```

Subclasses declare only their own subtype-specific fields. They do **not**
redeclare the shared fields (`id`, `caseId`, `namespaceId`, `timestamp`, audit
fields) — doing so causes SDN to report duplicate property definitions.

### CaseEventNodeMapper

All conversion between `CaseEvent` domain objects and `CaseEventNode` graph
projections is centralised in `CaseEventNodeMapper`. The mapper's `toDomain`,
`fromDomain`, and `withRemoved` methods each contain an exhaustive `when` over
the sealed hierarchy. Because `CaseEventNode` is a sealed class, the Kotlin
compiler enforces exhaustiveness — adding a new subtype without updating the
mapper is a compile error.

### JSON-serialised fields

Two node classes store fields as JSON strings rather than scalars:

| Node class | Field | Stores |
|---|---|---|
| `MessageEventNode` | `contentJson` | `List<MessageContent>` |
| `ToolResponseEventNode` | `outputJson` | Single `MessageContent` |
| `QuestionEventNode` | `options` | `List<String>?` |
| `IntegrationConfigNode` | `parametersJson` | `JsonNode?` |

`MessageContentSerializer` handles the `MessageContent` cases. It uses a
typed `ObjectMapper` writer to ensure the Jackson `@JsonTypeInfo` discriminator
is emitted for each element — without this, the `type` property is omitted and
deserialisation throws `InvalidTypeIdException`.

## Adding a New Entity to Neo4j

1. **Create the node class** in `persistence/neo4j/`. Follow the flat-properties
   convention. Inline `toDomain()` and `fromDomain()` for simple entities.
   For sealed hierarchies, add a node subclass and update the central mapper.

2. **Create the Spring Data repository interface** extending `Neo4jRepository<NodeClass, String>`.
   Add any custom `@Query` methods needed (e.g. `findActiveByParentId`).

3. **Create the domain repository implementation** (`Neo4jXxxRepository`) wrapping
   the Spring Data repository. Implement the `EntityRepository` interface.
   Mark `deleteByParent` with `@Transactional open override` — SDN requires
   `open` on transactional methods in Spring-managed classes.

4. **Register the bean** in `Neo4jPersistenceConfiguration` with a `@Bean` method.

5. **Add a test fixture** `TestXxxRepository` in
   `src/test/kotlin/io/whozoss/agentos/persistence/` for use in unit and
   service-level tests that run without Neo4j.

6. **Register the test fixture** in `TestPersistenceConfiguration`.

7. **Add persistence contract tests** by creating a subclass of the relevant
   `AbstractXxxPersistenceSpec` (or a new abstract spec) and activating it for
   both `embedded-neo4j` and Testcontainers modes.

## Testing Strategy

There are three distinct test contexts for persistence:

### 1. Unit and service-level tests (no Neo4j)

Tests that exercise service logic (e.g. `CaseServiceImplSpec`) use plain
in-memory repository fixtures (`TestCaseRepository`, `TestCaseEventRepository`,
etc.) instantiated directly — no Spring context, no Bolt connection. These
fixtures live in `src/test/kotlin/.../persistence/` and are also registered
as beans in `TestPersistenceConfiguration` for `@SpringBootTest` tests that
use `@ActiveProfiles("test")`.

### 2. Embedded Neo4j (harness) — fast, no Docker

Spring integration tests annotated with `@ActiveProfiles("test", "embedded-neo4j")`
use the Neo4j test harness (`org.neo4j.test:neo4j-harness`). The harness starts
an in-process Neo4j instance exposed over Bolt without the Netty 4.2 requirement
of the full embedded engine, avoiding the version conflict with Spring Boot's
Netty 4.1 pin.

`EmbeddedNeo4jTestConfiguration` provides the `Driver` bean. The harness
starts and stops with the Spring context. Database state is cleared in
`beforeEach` via `Neo4jContainerSupport.clearDatabase(driver)`.

This is the recommended mode for local development and CI when a Docker daemon
is not available.

### 3. Testcontainers — full fidelity, requires Docker

Tests subclassing `AbstractXxxPersistenceSpec` can be activated against a real
Neo4j server running in a Docker container via Testcontainers. The container is
a lazy singleton shared across all specs in the JVM run:

```kotlin
companion object {
    @JvmStatic
    @DynamicPropertySource
    fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
        Neo4jContainerSpec.registerProperties(registry)
}
```

The Docker image tag is `neo4j:2026.02-community` (defined in
`Neo4jContainerSupport.NEO4J_IMAGE`). Keep this in sync with `neo4jEmbedded`
in `libs.versions.toml` so both modes test against the same Neo4j release line.

Testcontainers tests are skipped automatically when Docker is unavailable.

### Abstract persistence contract specs

Each entity type has an `AbstractXxxPersistenceSpec` that defines the full
persistence contract (save, findByIds, findByParent, soft-delete, deleteByParent,
ordering). Concrete subclasses activate either the harness or Testcontainers mode
by selecting the appropriate Spring profile and configuration import. Both modes
inherit all test cases, ensuring they satisfy the same contract.

## Dependency Notes

The embedded Neo4j engine (`org.neo4j:neo4j:2026.x`) requires specific exclusions
to prevent classpath conflicts. See the `AgentOS - Neo4j embedded engine dependency:
required exclusions` memory entry for the full Gradle dependency declaration and
the rationale behind each exclusion.

Key points:
- Exclude the entire `org.slf4j` group from the Neo4j dependency to prevent
  `NOPLoggerFactory` from winning the SLF4J binding race over Logback.
- Exclude `neo4j-java-driver` to let the Spring Boot BOM version (5.x) win over
  Neo4j's requested version (6.x).
- The embedded engine requires Java 21+. AgentOS targets Java 25 (set in
  `libs.versions.toml`).
- The Neo4j test harness requires Netty 4.2 on the test classpath. This is
  applied to `testRuntimeClasspath` only via a forced resolution strategy in
  `agentos-service/build.gradle.kts`.
