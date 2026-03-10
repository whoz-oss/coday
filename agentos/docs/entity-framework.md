# Entity Framework

## Interfaces

All domain objects implement `Entity` from `agentos-sdk`, which provides a UUID id, audit timestamps, and a soft-delete flag via `EntityMetadata`.

`EntityService<EntityType, ParentId>` defines the CRUD contract. `EntityRepository<EntityType, ParentId>` is the persistence abstraction. `InMemoryEntityRepository` provides a thread-safe, sorted in-memory default implementation.

## Adding a New Entity

The chain is: domain model implementing `Entity` → repository extending `InMemoryEntityRepository` (or implementing `EntityRepository` for a real DB) → service implementing `EntityService` → controller extending `EntityController`.

The controller inherits standard endpoints: `GET /{id}`, `POST /by-ids`, `GET /by-parentId/{parentId}`, `POST`, `PUT /{id}`, `DELETE /{id}`. Any method can be overridden to customize or restrict behaviour.

## Soft Delete

`delete()` sets `metadata.removed = true`. All `findBy*` methods exclude removed entities by default. There is no hard delete.
