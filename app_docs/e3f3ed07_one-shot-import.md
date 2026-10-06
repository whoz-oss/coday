# B4-T1 one-shot filesystem import

## What changed

B4-T1 adds an offline migration path that reads the Factory’s filesystem state and converges a PostgreSQL-backed persistence store to it without changing the live server’s filesystem writers. The implementation covers the nine plans present in the change: workflow definitions, workflow instances, workflow evidence, human interactions, agent step attempts, agent step results, oracle executions, work environments, and deliveries.

`factory/src/adapters/persistence/migration/one-shot-import.ts` is the core. It discovers filesystem data under the supplied `dataRoot`, using the existing filesystem repository adapters (plus read-only structural readers for projections and journals), maps each aggregate to its SQL table, and writes each non-empty context inside `withTransaction`. Writes use primary-key `ON CONFLICT ... DO UPDATE` handling, so repeated imports converge rather than create duplicates. Organization and workstream scope default to the SQL defaults and can be overridden through the import options.

The same module exports `verifyImport`, which reloads both sides and produces a structured report with overall `ok`, per-context filesystem/SQL counts, and aggregate-level missing, extra, count, or canonical-hash discrepancies. Hashes use `computeCanonicalHash`; the report also includes total counts. `hashVerificationReport` provides a deterministic hash of the report object.

## Entrypoint and bundle surface

`factory/src/entrypoints/import-one-shot.ts` provides the executable PostgreSQL runner. It resolves `FACTORY_DATA_ROOT`, optional definitions/oracles roots, organization/workstream overrides, and PG connection settings through `resolveSqlDatabaseConfig`, creates the pool client, runs the import, prints a context summary plus JSON discrepancy lines, and sets exit code 0 only for an OK report (1 for discrepancies or failures).

`factory/src/adapters/persistence/index.ts` re-exports the import API and report types. The generated `factory/runtime/factory-operational.mjs` contains the bundled runtime exports used by the tests and operational consumers.

## Verification

`factory/tests/test-persistence-import.mjs` builds a temporary dataset using the existing filesystem facades, with one sample aggregate in every covered context. It runs against `createInMemorySqlClient`, checks successful count/hash verification, checks raw table counts, reruns the import to prove idempotence, and verifies two failure modes: deletion from SQL yields `MISSING_IN_SQL`, while changing an attempt payload yields `HASH_MISMATCH` with differing hashes.

The in-memory client update in `factory/tests/support/in-memory-sql-client.mjs` supports the SQL subset needed by the import’s upserts and verification queries, including conflict/update behavior and the relevant transaction/query parsing.

To run the focused offline check from the repository root:

```sh
node factory/tests/test-persistence-import.mjs
```

For a live import, provide `FACTORY_DATA_ROOT` and the `PG*` variables, then run the TypeScript entrypoint with the project’s supported runtime or use the bundled runner described in the entrypoint comments.

The task specification is recorded in `specs/e3f3ed07_b4_t1_one_shot_import.md`.
