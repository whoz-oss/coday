# V3 workflow core migration and schema validation

## What changed

Flyway V3 extends the PostgreSQL workflow pilot with visibility, explicit workstream grants, definition revision history, per-step execution state, and transition logging. The new relational structures are tenant-scoped and use composite foreign keys so child rows cannot cross organizations, workstreams, namespaces, or workflow instances. Instance children are deleted with their parent through `ON DELETE CASCADE`.

## Files carrying the change

- `factory/infra/migrations/V3__workflow_core.sql`
  - Extends `workflow_definitions` with `visibility` (default `organization`, constrained to `platform`, `organization`, or `workstream`) and nullable `owner_workstream_id`; it also defensively restores the `organization_id` default and adds lookup indexes.
  - Adds `workflow_definition_versions`, keyed by definition and revision, with JSONB definition data, timestamps, a composite FK to `workflow_definitions`, revision validation, and an `updated_at` trigger.
  - Adds `workstream_workflow_grants`, keyed by organization/workstream/workflow type, with optional version pinning, `enabled`, JSONB configuration, a composite FK to `workstreams`, and an `updated_at` trigger.
  - Adds `workflow_step_states`, keyed by workflow instance and step, with status/payload/revision data and a composite FK to `workflow_instances` covering `(organization_id, workstream_id, namespace_id, workflow_id)`.
  - Adds append-only `workflow_transitions`, keyed by workflow instance and transition, with transition payload and the same tenant-isolating composite FK. It intentionally has no `updated_at` trigger.
  - Uses the shared V2 `set_updated_at()` function for mutable V3 tables and standard tenant, JSONB, timestamp, and revision defaults.
- `factory/tests/test-v3-migration-schema.mjs`
  - Provides dependency-free offline parsing of the cumulative V1 + V2 + V3 SQL state.
  - Validates SQL cleanliness, tables/columns, primary and unique keys, composite FK targets and cascade behavior, visibility/revision checks, defaults, trigger wiring, and tenant-isolation guarantees.
- `factory/infra/README.md`
  - Documents V3 in the migration inventory, describes the four tables and visibility/grant behavior, records the composite-FK tenant-isolation guarantees, and adds the verification command.
- `specs/1ffb4f60_v3_flyway_migration_schema_test.md`
  - Records the implementation plan and verification strategy for this migration and schema test.

## Verification

Run the offline contract test from the repository root:

```bash
node factory/tests/test-v3-migration-schema.mjs
```

It passed with **25 checks and 0 failures** in this change. The test does not require PostgreSQL, Docker, or the `pg` driver.
