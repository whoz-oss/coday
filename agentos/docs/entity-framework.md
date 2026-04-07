# Entity Framework

## Interfaces

All domain objects implement `Entity` from `agentos-sdk`, which provides a UUID id, audit timestamps, and a soft-delete flag via `EntityMetadata`.

`EntityService<EntityType, ParentId>` defines the CRUD contract. `EntityRepository<EntityType, ParentId>` is the persistence abstraction. `InMemoryEntityRepository` provides a thread-safe, sorted in-memory default implementation.

## Adding a New Entity

The chain is: domain model implementing `Entity` → repository extending `InMemoryEntityRepository` (or implementing `EntityRepository` for a real DB) → service implementing `EntityService` → controller extending `EntityController`.

The controller inherits standard endpoints: `GET /{id}`, `POST /by-ids`, `GET /by-parentId/{parentId}`, `POST`, `PUT /{id}`, `DELETE /{id}`. Any method can be overridden to customize or restrict behaviour.

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
