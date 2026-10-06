# V2 tenant and membership schema

## What changed

Jalon B2 now has an authoritative Flyway migration at `factory/infra/migrations/V2__tenant_and_membership.sql`. It adds the 11 tenant-scoped tables:

- `organizations`, `workstreams`, and `squads` for the tenant hierarchy;
- `principals` and `service_identities` for organization identities;
- versioned `roles` and organization/workstream/squad membership tables;
- `repositories` and `workstream_repositories` for repository associations.

Every table has a non-null `organization_id` with `DEFAULT 'default'`. Mutable entities include `revision` with `DEFAULT 1 CHECK (revision >= 1)`, JSONB payload storage where applicable, and timestamps. Composite primary keys and explicit unique constraints support the composite foreign keys. Foreign keys propagate organization identity through the hierarchy and role references, preventing cross-tenant structural or role references; structural relationships use `ON DELETE CASCADE`.

The migration also defines the reusable PostgreSQL `set_updated_at()` trigger function and attaches `BEFORE UPDATE` triggers to the seven tables carrying `updated_at`: organizations, workstreams, squads, principals, service identities, roles, and repositories.

## Documentation and validation

`factory/infra/README.md` now describes V2 in the migration inventory, documents the complete table/constraint overview, explains tenant isolation and timestamp triggers, and gives the offline validation command. The existing V1 migration and runtime code were not changed.

`factory/tests/test-v2-migration-schema.mjs` is a dependency-free Node ESM validation runner. It parses the migration without PostgreSQL, Docker, or the `pg` driver and checks SQL cleanliness, all 11 tables and columns, primary and explicit unique keys, composite foreign keys and cascade actions, uniqueness of FK targets, revision and subject-type checks, organization defaults, the trigger function, and updated-at triggers. Run it from the repository root:

```bash
node factory/tests/test-v2-migration-schema.mjs
```

The verification completed successfully with **23 checks passed and 0 failed**. A live database can additionally apply the migration through the Flyway/docker-compose instructions in `factory/infra/README.md`.

## Files in this change

- `factory/infra/migrations/V2__tenant_and_membership.sql` — schema migration and trigger definitions.
- `factory/tests/test-v2-migration-schema.mjs` — offline schema parser and integrity checks.
- `factory/infra/README.md` — migration usage and V2 schema documentation.
- `specs/cf4b6233_tenant_membership_v2_migration.md` — implementation plan and verification notes added with the change.
