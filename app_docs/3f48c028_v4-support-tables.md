# V4 support-table migration

## What changed

Flyway migration V4 adds the durable Factory support tables for asynchronous effects and idempotent command handling:

- `factory/infra/migrations/V4__outbox_and_idempotency.sql` creates tenant-scoped `outbox_events` and `idempotency_records`.
- `outbox_events` uses `(organization_id, id)` as its primary key, stores JSONB event payloads, tracks `pending`/`dispatched`/`failed` delivery state and non-negative retry attempts, and has the tenant-scoped drain index `idx_outbox_events_drain` on `(organization_id, status, created_at)`. It is intentionally an append-style status log without `updated_at` or an update trigger.
- `idempotency_records` uses `(organization_id, idempotency_key)` as its primary key and also declares the explicit `uq_idempotency_records` uniqueness constraint. It stores the request hash, optional resource reference, lifecycle status, cached JSONB response, and timestamps; `trg_idempotency_records_updated_at` calls the shared `set_updated_at()` function before updates. Both tables default `organization_id` to `'default'`.

The migration includes status checks, JSONB/timestamp defaults, and an operations index on `(organization_id, workstream_id)`. The design rationale in the migration and README ties request hashing and deduplication to Amendments 2 and 4, and the transactional outbox/drain-retry model to Amendment 7.

## Schema validation

`factory/tests/test-v4-migration-schema.mjs` is an offline Node.js schema test. It reads V1, V2, V3, and V4, parses the SQL without PostgreSQL, Docker, or `pg`, and checks statement cleanliness, both table definitions, types and nullability, defaults, tenant-scoped keys, idempotency uniqueness, the outbox drain index, status/attempt checks, and the `set_updated_at()` trigger. Run:

```bash
node factory/tests/test-v4-migration-schema.mjs
```

A zero exit status means all checks passed.

## Documentation

`factory/infra/README.md` now lists V4 in the migration tree and migration overview, and appends a V4 schema section describing both tables, their keys, checks, indexes, trigger behavior, Amendment 2/4/7 rationale, and the offline validation command. The change set also contains the generated task specification at `specs/3f48c028_v4_outbox_idempotency_migration.md`.

## Files in the change

- `factory/infra/migrations/V4__outbox_and_idempotency.sql`
- `factory/tests/test-v4-migration-schema.mjs`
- `factory/infra/README.md`
- `specs/3f48c028_v4_outbox_idempotency_migration.md`
