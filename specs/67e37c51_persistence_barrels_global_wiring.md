# Implementation Plan: Jalon C1-T2wiring: Persistence Barrels Export, Global Wiring Test, and Bundle Regeneration

Wire the scheduling, liveness, and lease persistence components (`WorkUnitRepository`, `WorkerRepository`, `LeaseRepository`) into the factory barrels, extend the global port/adapter wiring test (`test-sql-repository-ports-adapters.mjs`), and regenerate the operational bundle (`factory/runtime/factory-operational.mjs`).

---

## Technical Context

In previous tasks (Jalon C1-T1a and C1-T1b), domain types, ports, SQL adapters, and conformance tests were created for `WorkUnitRepository`, `WorkerRepository`, and `LeaseRepository`:
- Ports: `factory/src/ports/persistence/work-unit-repository.ts`, `worker-repository.ts`, `lease-repository.ts`
- SQL Adapters: `factory/src/adapters/persistence/sql/sql-work-unit-repository.ts`, `sql-worker-repository.ts`, `sql-lease-repository.ts`

These components now need to be exported through the persistence barrel files so they become part of the Factory TypeScript surface and are bundled into `factory/runtime/factory-operational.mjs`. In addition, the global wiring parity test (`factory/tests/test-sql-repository-ports-adapters.mjs`) must be updated to include these 3 new persistence ports alongside the existing 9 persistence ports.

---

## Touch File Matrix

| File Path | Description of Changes |
|-----------|------------------------|
| `factory/src/ports/persistence/index.ts` | Export `WorkUnitRepository`, `WorkerRepository`, and `LeaseRepository` types, scopes, filters, error codes, and options. |
| `factory/src/adapters/persistence/sql/index.ts` | Export SQL classes, factory functions (`createSql*`), error classes, and options types for work-unit, worker, and lease adapters. |
| `factory/src/adapters/persistence/index.ts` | Re-export the SQL adapter exports (`SqlWorkUnitRepository`, `createSqlWorkUnitRepository`, `SqlWorkUnitRepositoryError`, `type SqlWorkUnitRepositoryOptions`, `SqlWorkerRepository`, `createSqlWorkerRepository`, `SqlWorkerRepositoryError`, `type SqlWorkerRepositoryOptions`, `SqlLeaseRepository`, `createSqlLeaseRepository`, `type SqlLeaseRepositoryOptions`) from `./sql/index.js`. |
| `factory/tests/test-sql-repository-ports-adapters.mjs` | Import new SQL adapters & factories from `factory-operational.mjs`; update `REPOSITORY_PORTS` descriptor list and scenario assertions to support ports without filesystem adapters cleanly. |
| `factory/runtime/factory-operational.mjs` | Regenerated via `node factory/toolchain/build.mjs`. |

---

## Detailed Step-by-Step Instructions

### Step 1: Update `factory/src/ports/persistence/index.ts`

Add exports for `work-unit-repository.js`, `worker-repository.js`, and `lease-repository.js`:

```typescript
export type {
  WorkUnitRepository,
  WorkUnitRepositoryScope,
  WorkUnitListFilter,
} from './work-unit-repository.js'

export type {
  WorkerRepository,
  WorkerRepositoryScope,
  WorkerListFilter,
} from './worker-repository.js'

export type {
  LeaseRepository,
  AcquireLeaseOptions,
  AcquireLeaseResult,
  RenewLeaseOptions,
  ReleaseLeaseOptions,
  ExpireLeasesOptions,
} from './lease-repository.js'
```

### Step 2: Update `factory/src/adapters/persistence/sql/index.ts`

Add exports for `sql-work-unit-repository.js`, `sql-worker-repository.js`, and `sql-lease-repository.js`:

```typescript
export {
  SqlWorkUnitRepository,
  createSqlWorkUnitRepository,
  SqlWorkUnitRepositoryError,
  type SqlWorkUnitRepositoryOptions,
} from './sql-work-unit-repository.js'

export {
  SqlWorkerRepository,
  createSqlWorkerRepository,
  SqlWorkerRepositoryError,
  type SqlWorkerRepositoryOptions,
} from './sql-worker-repository.js'

export {
  SqlLeaseRepository,
  createSqlLeaseRepository,
  type SqlLeaseRepositoryOptions,
} from './sql-lease-repository.js'
```

### Step 3: Update `factory/src/adapters/persistence/index.ts`

Re-export the new SQL repositories, error classes, options, and factories from `./sql/index.js`:

```typescript
  SqlWorkUnitRepository,
  createSqlWorkUnitRepository,
  SqlWorkUnitRepositoryError,
  type SqlWorkUnitRepositoryOptions,
  SqlWorkerRepository,
  createSqlWorkerRepository,
  SqlWorkerRepositoryError,
  type SqlWorkerRepositoryOptions,
  SqlLeaseRepository,
  createSqlLeaseRepository,
  type SqlLeaseRepositoryOptions,
```

Add these to the `export { ... } from './sql/index.js'` block at the bottom of `factory/src/adapters/persistence/index.ts`.

### Step 4: Regenerate Operational Bundle

Run the build toolchain script:
```bash
node factory/toolchain/build.mjs
```
*Note: Do NOT edit `factory/runtime/factory-operational.mjs` directly.*

### Step 5: Extend Global Wiring Test `factory/tests/test-sql-repository-ports-adapters.mjs`

1. Update imports from `../runtime/factory-operational.mjs` to include:
   - `SqlWorkUnitRepository`, `createSqlWorkUnitRepository`
   - `SqlWorkerRepository`, `createSqlWorkerRepository`
   - `SqlLeaseRepository`, `createSqlLeaseRepository`

2. Update `REPOSITORY_PORTS` array:
   Add entries for `work-unit`, `worker`, and `lease`:
   ```javascript
   {
     port: 'work-unit',
     sql: SqlWorkUnitRepository,
     createSql: createSqlWorkUnitRepository,
   },
   {
     port: 'worker',
     sql: SqlWorkerRepository,
     createSql: createSqlWorkerRepository,
   },
   {
     port: 'lease',
     sql: SqlLeaseRepository,
     createSql: createSqlLeaseRepository,
   },
   ```

3. Adjust the `wiringSuite` scenario assertions in `test-sql-repository-ports-adapters.mjs`:
   - In `${descriptor.port}: filesystem and SQL adapters are exported constructors`:
     For filesystem check, check `descriptor.filesystem`:
     ```javascript
     if (descriptor.filesystem) {
       assert.equal(
         typeof descriptor.filesystem,
         'function',
         `${descriptor.port}: filesystem adapter is not an exported constructor`
       )
     }
     ```
   - In `${descriptor.port}: SQL surface covers the filesystem surface`:
     Guard on `descriptor.filesystem`:
     ```javascript
     if (descriptor.filesystem) {
       const sqlMethods = prototypeMethods(descriptor.sql)
       for (const method of prototypeMethods(descriptor.filesystem)) {
         assert.ok(sqlMethods.includes(method), `${descriptor.port}: SQL adapter is missing the "${method}" method`)
       }
     }
     ```

### Step 6: Verification

Execute the test commands:
1. `node factory/tests/test-sql-repository-ports-adapters.mjs` -> Verify all 12 ports pass wiring checks.
2. `node factory/tests/test-conformance-workunit-worker.mjs` -> Verify passing.
3. `node factory/tests/test-lease-protocol.mjs` -> Verify passing.
4. `node factory/tests/test-repository-ports-adapters.mjs` -> Verify passing.
5. `node factory/tests/test-sql-unit-of-work.mjs` -> Verify passing.

---

## Constraints Verification
- Domain / adapter business logic in W2 (`work-unit.ts`, `worker.ts`, `lease.ts`, `sql-work-unit-repository.ts`, `sql-worker-repository.ts`, `sql-lease-repository.ts`) remains UNTOUCHED.
- DB migrations and `agentos/**` remain UNTOUCHED.
- `factory/runtime/factory-operational.mjs` is strictly built via `node factory/toolchain/build.mjs`.
