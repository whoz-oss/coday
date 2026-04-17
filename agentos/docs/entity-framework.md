# Entity Framework

## Core Concepts

All domain objects implement `Entity` (sdk: `entity/Entity.kt`), which exposes a UUID `id` computed from a mandatory `EntityMetadata` field. `EntityMetadata` carries identity, audit timestamps (`created`, `createdBy`, `modified`, `modifiedBy`), and the soft-delete flag `removed`.

All deletes are soft: `removed = true`. Active-only filtering is applied in every `findBy*` method — callers never see removed entities.

Domain entities must be annotated `@JsonIgnoreProperties(ignoreUnknown = true)` because `Entity` exposes a computed `id` property that Jackson serialises on write but cannot find in the constructor on read.

## Abstractions

`EntityRepository<T, P>` (service: `entity/EntityRepository.kt`) is the persistence contract: `save`, `findByIds`, `findByParent`, `delete`, `deleteByParent`. `P` is the parent identifier type (typically `UUID`).

`EntityService<T, P>` (service: `entity/EntityService.kt`) is the business-logic contract with the same shape plus a `getById` convenience that throws `ResourceNotFoundException` on miss.

`EntityController<EntityType, ParentIdentifier, ResourceType>` (service: `entity/EntityController.kt`) is the abstract REST base. Concrete controllers extend it, declare `@RestController` + `@RequestMapping`, and implement two mapping methods:
- `toResource(entity)` — domain to HTTP DTO
- `toDomain(resource)` — HTTP DTO to domain

Six endpoints are inherited: `GET /{id}`, `POST /by-ids`, `GET /by-parentId/{parentId}`, `POST`, `PUT /{id}`, `DELETE /{id}`. Any can be overridden.

## Entity vs Resource

Domain entities are never exposed directly. Each controller defines a companion **resource class** (the HTTP DTO). Conventions:
- Resource class lives alongside the controller (e.g. `AgentConfigResource` next to `AgentConfigController`).
- Bean Validation annotations (`@NotBlank`, `@NotNull`, ...) belong on the resource, not on the domain entity.
- The resource is annotated `@Schema(name = "Foo")` so the OpenAPI spec uses the clean name, not `FooResource`.

## Persistence

Two modes are available, selected via `agentos.persistence.mode`:

| Mode | Beans active |
|---|---|
| `embedded-neo4j` (default) | `Neo4jPersistenceConfiguration` active; in-process Neo4j engine |
| `neo4j` | `Neo4jPersistenceConfiguration` active; standalone Neo4j server |

`Neo4jPersistenceConfiguration` (`config/Neo4jPersistenceConfiguration.kt`) registers one `@Bean` per entity type, wiring the Spring Data Neo4j interface into a hand-written implementation class.

### Neo4j persistence layer

For each entity, three files live alongside the domain classes in the entity's package:

- **`*Node`** — `@Node` data class with all fields flattened (no nested objects; `EntityMetadata` fields inlined). `removed` is stored as `Boolean?` where `null` means active and `true` means soft-deleted. Provides `toDomain()` and a companion `fromDomain()`.
- **`*NodeNeo4jRepository`** — Spring Data Neo4j interface extending `Neo4jRepository<*Node, String>`. Active-filter queries are always written by hand (`WHERE removed IS NULL OR removed = false`) — do not rely on SDN derived queries for nullable booleans.
- **`Neo4j*Repository`** — hand-written implementation of the domain `*Repository` interface. `deleteByParent` must be `open` for CGLIB proxying of `@Transactional`.

Every `Neo4j*Repository` bean must be registered in `Neo4jPersistenceConfiguration`. Omitting the registration breaks the entire Spring context under Neo4j profiles, causing all Neo4j integration tests to fail.

## Adding a New Entity

Use `AgentConfig` (namespace-scoped, UUID parent) or `Namespace` (root-level, String parent) as reference implementations. The required files per entity are:

```
<package>/
  MyEntity.kt                          # data class : Entity, @JsonIgnoreProperties
  MyEntityRepository.kt                # interface : EntityRepository<MyEntity, UUID>
  MyEntityService.kt                   # interface : EntityService<MyEntity, UUID>
  MyEntityServiceImpl.kt               # @Service, delegates to repository
  MyEntityResource.kt                  # HTTP DTO, @Schema(name="MyEntity"), Bean Validation
  MyEntityController.kt                # @RestController, extends EntityController
  MyEntityNode.kt                      # @Node, flat fields, toDomain/fromDomain
  MyEntityNodeNeo4jRepository.kt       # Neo4jRepository<MyEntityNode, String>
  Neo4jMyEntityRepository.kt           # open class, @Transactional on deleteByParent
config/
  Neo4jPersistenceConfiguration.kt     # add one @Bean
```

After adding the controller, regenerate the OpenAPI spec and TypeScript client:

```bash
nx run agentos-service:regenerate
```

Commit the updated `agentos/openapi/agentos-openapi.yaml` alongside the Kotlin changes. CI fails if the committed spec diverges from the generated one.
