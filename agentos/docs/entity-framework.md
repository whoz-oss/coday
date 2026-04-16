# Entity Framework

## Interfaces

All domain objects implement `Entity` from `agentos-sdk`, which provides a UUID id, audit timestamps, and a soft-delete flag via `EntityMetadata`.

`EntityService<EntityType, ParentId>` defines the CRUD contract. `EntityRepository<EntityType, ParentId>` is the persistence abstraction. The SDK also ships `InMemoryEntityRepository`, a thread-safe, sorted in-memory base — it is used by in-memory repository subclasses (one per entity package) that activate under the default `in-memory` persistence mode and in the `test` profile. Production runs use Neo4j-backed implementations (see below).

## Entity vs Resource (DTO)

Domain entities are never exposed directly by controllers. Each controller defines a companion **resource class** (HTTP DTO) that represents the API contract. The two evolve independently.

Conventions:
- The resource class lives alongside the controller (e.g. `UserResource` next to `UserController`).
- Bean Validation annotations (`@NotBlank`, `@Email`, …) belong on the resource, not on the domain entity — keeping the domain model clean.
- The resource is annotated `@Schema(name = "Foo")` so the OpenAPI spec uses the clean name (`User`, not `UserResource`).
- Mapping between entity and resource is done in the controller via `toResource()` / `toDomain()` overrides on `EntityController`.
- Business logic (e.g. identity resolution) stays in the service; the controller only converts and delegates.

## Soft Delete

`delete()` sets `metadata.removed = true`. All `findBy*` methods exclude removed entities by default. There is no hard delete.

---

## Adding a New Entity

Use `AgentConfig` (namespace-scoped, UUID parent) or `Namespace` (root-level, String parent) as reference implementations.

### 1. Domain model

**Location:** `agentos-service/src/main/kotlin/io/whozoss/agentos/<package>/MyEntity.kt`

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)  // required: Entity exposes computed `id`
data class MyEntity(
    override val metadata: EntityMetadata = EntityMetadata(),
    val parentId: UUID,
    val name: String,
    val description: String? = null,
) : Entity
```

`@JsonIgnoreProperties(ignoreUnknown = true)` is mandatory because the `Entity` interface exposes a computed `id` property that Jackson serialises on write but cannot deserialise back from the constructor.

### 2. Repository interface

**Location:** `agentos-service/src/main/kotlin/io/whozoss/agentos/<package>/MyEntityRepository.kt`

```kotlin
interface MyEntityRepository : EntityRepository<MyEntity, UUID>
```

The `ParentId` type matches the field used to scope the entity (usually `UUID`, or `String` for root-level entities like `Namespace`).

### 3. In-memory implementation

**Location:** `agentos-service/src/main/kotlin/io/whozoss/agentos/<package>/InMemoryMyEntityRepository.kt`

Required for the `test` profile and for the OpenAPI spec generation task, which starts the application without Neo4j.

```kotlin
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryMyEntityRepository :
    MyEntityRepository,
    EntityRepository<MyEntity, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.parentId },
        comparator = compareBy { it.name },
    )
```

### 4. Neo4j persistence

Three files in `persistence/neo4j/`, plus a line in `Neo4jPersistenceConfiguration`.

**`MyEntityNode.kt`** — flat `@Node` data class. Keep `EntityMetadata` fields inlined. Use `removed: Boolean? = null` (null = not removed, true = soft-deleted):

```kotlin
@Node("MyEntity")
data class MyEntityNode(
    @Id val id: String,
    val parentId: String,
    val name: String,
    val description: String? = null,
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): MyEntity = MyEntity(
        metadata = EntityMetadata(
            id = UUID.fromString(id),
            created = created, createdBy = createdBy,
            modified = modified, modifiedBy = modifiedBy,
            removed = removed ?: false,
        ),
        parentId = UUID.fromString(parentId),
        name = name, description = description,
    )
    companion object {
        fun fromDomain(e: MyEntity): MyEntityNode = MyEntityNode(
            id = e.id.toString(), parentId = e.parentId.toString(),
            name = e.name, description = e.description,
            created = e.metadata.created, createdBy = e.metadata.createdBy,
            modified = e.metadata.modified, modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )
    }
}
```

If the entity has a JSON field (like `IntegrationConfig.parameters`), store it as a `String?` column and round-trip via `ObjectMapper`.

**`MyEntityNodeNeo4jRepository.kt`** — always write the active-filter query by hand; do not rely on SDN derived queries for nullable booleans:

```kotlin
interface MyEntityNodeNeo4jRepository : Neo4jRepository<MyEntityNode, String> {
    @Query(
        "MATCH (e:MyEntity) " +
        "WHERE e.parentId = \$parentId AND (e.removed IS NULL OR e.removed = false) " +
        "RETURN e ORDER BY e.name ASC"
    )
    fun findActiveByParentId(parentId: String): List<MyEntityNode>
}
```

**`Neo4jMyEntityRepository.kt`** — `deleteByParent` must be `open` so CGLIB can proxy `@Transactional`:

```kotlin
open class Neo4jMyEntityRepository(
    private val neo4jRepository: MyEntityNodeNeo4jRepository,
) : MyEntityRepository {
    override fun save(entity: MyEntity): MyEntity =
        neo4jRepository.save(MyEntityNode.fromDomain(entity)).toDomain()
    override fun findByIds(ids: Collection<UUID>): List<MyEntity> =
        neo4jRepository.findAllById(ids.map { it.toString() })
            .filter { it.removed != true }.map { it.toDomain() }
    override fun findByParent(parentId: UUID): List<MyEntity> =
        neo4jRepository.findActiveByParentId(parentId.toString()).map { it.toDomain() }
    override fun delete(id: UUID): Boolean =
        neo4jRepository.findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { neo4jRepository.save(it.copy(removed = true)); true } ?: false
    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByParentId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        return active.size
    }
    companion object : KLogging()
}
```

**`Neo4jPersistenceConfiguration.kt`** — add one `@Bean`:

```kotlin
@Bean
fun neo4jMyEntityRepository(
    myEntityNodeNeo4jRepository: MyEntityNodeNeo4jRepository,
): MyEntityRepository {
    logger.info { "[Persistence] Neo4jMyEntityRepository active" }
    return Neo4jMyEntityRepository(myEntityNodeNeo4jRepository)
}
```

> **Critical:** omitting this registration causes the Spring context to fail in both `neo4j` and `embedded-neo4j` profiles — and this breaks *all* Neo4j integration tests, not just the new ones, because the entire context fails to load.

### 5. Service interface + impl

```kotlin
// MyEntityService.kt
interface MyEntityService : EntityService<MyEntity, UUID>

// MyEntityServiceImpl.kt
@Service
class MyEntityServiceImpl(
    private val myEntityRepository: MyEntityRepository,
) : MyEntityService {
    override fun create(entity: MyEntity): MyEntity = myEntityRepository.save(entity)
    override fun update(entity: MyEntity): MyEntity = myEntityRepository.save(entity)
    override fun findByIds(ids: Collection<UUID>): List<MyEntity> = myEntityRepository.findByIds(ids)
    override fun findByParent(parentId: UUID): List<MyEntity> = myEntityRepository.findByParent(parentId)
    override fun delete(id: UUID): Boolean = myEntityRepository.delete(id)
    override fun deleteByParent(parentId: UUID): Int = myEntityRepository.deleteByParent(parentId)
}
```

### 6. HTTP resource + controller

```kotlin
// MyEntityResource.kt
@Schema(name = "MyEntity")
data class MyEntityResource(
    val id: UUID? = null,
    @field:NotNull(message = "parentId must not be null") val parentId: UUID,
    @field:NotBlank(message = "name must not be blank") val name: String,
    val description: String? = null,
)

// MyEntityController.kt
@RestController
@RequestMapping("/api/my-entities", produces = [MediaType.APPLICATION_JSON_VALUE])
class MyEntityController(
    private val myEntityService: MyEntityService,
) : EntityController<MyEntity, UUID, MyEntityResource>(myEntityService) {
    override fun toResource(entity: MyEntity): MyEntityResource = MyEntityResource(
        id = entity.metadata.id, parentId = entity.parentId,
        name = entity.name, description = entity.description,
    )
    override fun toDomain(resource: MyEntityResource): MyEntity = MyEntity(
        metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
        parentId = resource.parentId, name = resource.name, description = resource.description,
    )
    companion object : KLogging()
}
```

The six standard endpoints (`GET /{id}`, `POST /by-ids`, `GET /by-parentId/{parentId}`, `POST`, `PUT /{id}`, `DELETE /{id}`) are inherited. Override only what needs different behaviour.

### 7. Tests

Two complementary test classes (see `testing.md` for the full rationale):

**`MyEntityControllerSpec`** (StringSpec, no Spring) — instantiate the controller directly with MockK stubs. Cover `toResource`, `toDomain`, and every inherited endpoint (happy path + 404 cases).

**`MyEntityControllerMvcTest`** (`@SpringBootTest` + `@ActiveProfiles("test")`) — verify that `@Valid` fires through the dispatcher. Test at minimum: missing required field → 400, blank required field → 400, valid payload → 201/200. The `test` profile activates in-memory persistence, so no Neo4j needed.

### 8. Regenerate the OpenAPI spec

```bash
nx run agentos-service:regenerate
```

Commit the updated `agentos/openapi/agentos-openapi.yaml` alongside the Kotlin changes. CI fails if the committed spec diverges from the generated one.

---

## Persistence modes

| `agentos.persistence.mode` | What activates | When used |
|---|---|---|
| `in-memory` (default) | `InMemory*Repository` classes (one per entity package) | Local dev without Neo4j, `test` profile |
| `neo4j` | `Neo4jPersistenceConfiguration` + SDN repositories | Production, staging |
| `embedded-neo4j` | Same as `neo4j` but with harness-managed Driver | Neo4j integration tests (CI) |

When adding a new entity, implementations for **both** modes are required. Missing either causes the Spring context to fail on startup for the corresponding profile.

## Package layout

```
agentos-service/src/main/kotlin/io/whozoss/agentos/
  <package>/
    MyEntity.kt
    MyEntityRepository.kt
    InMemoryMyEntityRepository.kt
    MyEntityService.kt
    MyEntityServiceImpl.kt
    MyEntityResource.kt
    MyEntityController.kt
  persistence/neo4j/
    MyEntityNode.kt
    MyEntityNodeNeo4jRepository.kt
    Neo4jMyEntityRepository.kt
  config/
    Neo4jPersistenceConfiguration.kt     ← add one @Bean here
```
