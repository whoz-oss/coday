# Neo4j Persistence

## Persistence Modes

Selected via `agentos.persistence.mode` / `AGENTOS_PERSISTENCE_MODE`:

| Mode | When to use | Neo4j required |
|---|---|---|
| `embedded-neo4j` | Local single-user. **Default.** | No — in-process engine, Bolt on `localhost:7688` |
| `neo4j` | Multi-user server deployment | Yes — standalone server on `localhost:7687` |
| `in-memory` | Ephemeral / testing only (deprecated) | No |

`embedded-neo4j` stores data under `<agentos.persistence.data-dir>/neo4j/` (default: `data/neo4j/`).
Port is configurable via `agentos.persistence.embedded-bolt-port`; `application-embedded-neo4j.yml` sets `spring.neo4j.uri` automatically.

## Spring Configuration Wiring

`Neo4jPersistenceConfiguration` is active for both `neo4j` and `embedded-neo4j` modes. It enables
`@EnableNeo4jRepositories` scanning on `io.whozoss.agentos.persistence.neo4j` and declares one `@Bean`
per entity type, wrapping the Spring Data `Neo4jRepository` in a domain-facing `EntityRepository`.

## Node / Domain Mapping Pattern

Every domain entity has a `*Node` class carrying `@Node` and `@Id`. The domain class never touches SDN annotations.

**Key conventions:**
- **Flat properties** — `EntityMetadata` fields inlined as scalars; no nested objects on the node.
- **`@Id` is `String`** — UUIDs serialised to string; domain uses `UUID`. Conversion in `toDomain()`/`fromDomain()`.
- **`removed` is `Boolean?`** — `null` means active; always filter with `WHERE e.removed IS NULL OR e.removed = false`. Write `null` for active entities (`removed.takeIf { it }`), not `false`.
- **Scalar denormalisation** — always store the parent id as a scalar property (e.g. `namespaceId: String`) alongside the graph edge. `toDomain()` reads from the scalar; `save()` returns the node without the `@Relationship` field injected.

### Parent Relationships as Graph Edges

Parent links are real Neo4j relationships: `(:Case)-[:BELONGS_TO]->(:Namespace)`, etc.

**Writing:** never set the `@Relationship` field to a stub node in `fromDomain` — SDN overwrites the real node's data. Instead: save the owning node first, then call a dedicated `@Query` that MATCHes both nodes and MERGEs only the edge:

```kotlin
override fun save(entity: Case): Case =
    caseNodeNeo4jRepository
        .save(CaseNode.fromDomain(entity))
        .also { caseNodeNeo4jRepository.linkCaseToNamespace(it.id, entity.namespaceId.toString()) }
        .toDomain()
```

**Reading:** SDN 7 only injects `@Relationship` fields when the query returns `node, rel, related` explicitly.
For `CaseEvent` use `OPTIONAL MATCH` (the edge may not exist yet when `findActiveByCaseId` runs right after save):

```cypher
MATCH (e:CaseEvent) WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
OPTIONAL MATCH (e)-[r:BELONGS_TO]->(c:Case)
RETURN e, r, c ORDER BY e.timestamp ASC, e.id ASC
```

### `toDomain()` / `fromDomain()`

Simple entities embed both methods on the node class. `CaseEventNode` uses a dedicated
`CaseEventNodeMapper` (sealed hierarchy requires a central dispatch; compiler enforces exhaustiveness).
`withRemoved` in the mapper copies the `case` relationship field to preserve the `BELONGS_TO` edge.

## CaseEvent: Dual-Label Strategy

Each subtype gets two labels: primary `:CaseEvent` (allows cross-subtype queries) + secondary subtype
label e.g. `:MessageEvent` (SDN uses it to instantiate the right subclass).

```kotlin
@Node("CaseEvent") sealed class CaseEventNode(...)
@Node("MessageEvent") class MessageEventNode(...) : CaseEventNode(...)
```

Subclasses declare **only their own fields** — never redeclare shared fields (`id`, `caseId`, `timestamp`, ...) or SDN reports duplicate property definitions.

### JSON-serialised fields

| Node class | Field | Stores |
|---|---|---|
| `MessageEventNode` | `contentJson` | `List<MessageContent>` |
| `ToolResponseEventNode` | `outputJson` | Single `MessageContent` |
| `QuestionEventNode` | `options` | `List<String>?` |
| `IntegrationConfigNode` | `parametersJson` | `JsonNode?` |

`MessageContentSerializer` uses a typed `ObjectMapper` writer to emit the `@JsonTypeInfo` discriminator — omitting it causes `InvalidTypeIdException` on deserialisation.

## `@Query` String Literals

Always use Kotlin 2's raw string delimiter `$"""..."""`. Inside it, `$paramName` is a literal
dollar sign — exactly what Cypher expects:

```kotlin
@Query($"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
           WHERE ns.id = $namespaceId AND (c.removed IS NULL OR c.removed = false)
           RETURN c, r, ns ORDER BY c.created ASC""")
fun findActiveByNamespaceId(namespaceId: String): List<CaseNode>
```

Never use `"""...$param..."""` — Kotlin interpolates `$param` as a variable and fails with "unresolved reference".

## Adding a New Entity to Neo4j

1. **Node class** in `persistence/neo4j/` — flat properties, `toDomain()`/`fromDomain()` inline (or update the central mapper for sealed hierarchies).
2. **Spring Data repo interface** extending `Neo4jRepository<NodeClass, String>` with custom `@Query` methods.
3. **Domain repo implementation** (`Neo4jXxxRepository`) implementing `EntityRepository`. Mark `deleteByParent` as `@Transactional open override`.
4. **Register the bean** in `Neo4jPersistenceConfiguration`.
5. **Test fixture** `TestXxxRepository` in `src/test/kotlin/.../persistence/`.
6. **Register the test fixture** in `TestPersistenceConfiguration`.
7. **Persistence contract tests** — subclass the relevant `AbstractXxxPersistenceSpec` for both embedded-harness and Testcontainers modes.

## Testing Strategy

| Context | How | When |
|---|---|---|
| Unit / service-level | Direct instantiation of `TestXxxRepository` fixtures, no Spring context | Service logic tests |
| Embedded harness | `@ActiveProfiles("test", "embedded-neo4j")` + `EmbeddedNeo4jTestConfiguration` | Local dev, CI without Docker |
| Testcontainers | `Neo4jContainerSpec.registerProperties()` via `@DynamicPropertySource` | Full-fidelity, requires Docker |

Database state is cleared in `beforeEach` via `Neo4jContainerSupport.clearDatabase(driver)`.
Docker image: `neo4j:2026.02-community` (keep in sync with `neo4jEmbedded` in `libs.versions.toml`).

Each entity type has an `AbstractXxxPersistenceSpec` defining the full persistence contract. Both
harness and Testcontainers subclasses inherit all test cases.

## Dependency Notes

The embedded engine (`org.neo4j:neo4j:2026.x`) requires Gradle exclusions — see the
`AgentOS - Neo4j embedded engine dependency: required exclusions` memory entry. Key points:

- Exclude the entire `org.slf4j` group to prevent `NOPLoggerFactory` winning the SLF4J binding race.
- Exclude `neo4j-java-driver` to let the Spring Boot BOM version (5.x) win over Neo4j's requested 6.x.
- Requires Java 21+ (AgentOS targets Java 25).
- Neo4j test harness requires Netty 4.2 — applied to `testRuntimeClasspath` only via a forced resolution strategy in `agentos-service/build.gradle.kts`.
