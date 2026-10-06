# Spec & Plan: B4-T2 Shadow-Read + Bascule Writer Unique + Rollback

## Summary
Implement B4-T2 persistence authority switching, shadow-read comparison, and rollback mechanisms in `factory/dashboard/composition-root.mjs` (and supporting wiring/tests in `factory/`), keeping all store/adapter logic in `composition-root.mjs` clean, backwards-compatible (`FACTORY_PERSISTENCE=fs` by default), and verified by offline node unit/integration tests with in-memory SQL clients.

---

## 1. System Context & Architecture Requirements

### 1.1 Goals & Boundaries
1. Progressive & reversible transition of Coday Factory persistence authority to PostgreSQL via `composition-root.mjs`.
2. Default runtime behavior (`FACTORY_PERSISTENCE=fs` or unset) guarantees zero regressions.
3. Shadow-read mode (`FACTORY_PERSISTENCE_SHADOW=true` when `FACTORY_PERSISTENCE=fs`) compares filesystem reads against PostgreSQL reads in real-time or asynchronously/safely without modifying served HTTP responses or failing reads on SQL/comparison errors.
4. Switch to SQL (`FACTORY_PERSISTENCE=sql`): routes reads and writes exclusively to PostgreSQL repositories. Filesystem stores receive **0 writes**.
5. Rollback to FS (`FACTORY_PERSISTENCE=fs`): restores filesystem authority cleanly.
6. Documentation & Testing: clear switch & rollback guide with containerized Pg validation steps, and offline automated test suite using `createInMemorySqlClient`.

### 1.2 Strict Code Boundaries
- **Touch**: `factory/dashboard/composition-root.mjs`, `factory/tests/test-persistence-shadow-and-switch.mjs`, `factory/tests/test-composition-root-source.mjs` (if updating assertion counts), `factory/PERSISTENCE_SWITCH_ROLLBACK.md` (or `factory/docs/PERSISTENCE_SWITCH_ROLLBACK.md`).
- **DO NOT Touch / Modify**:
  - Existing persistence repositories/adapters (`factory/src/adapters/persistence/*`, `filesystem-*`, `sql-*`), which are frozen.
  - B4-T1 one-shot import (`factory/src/adapters/persistence/migration/one-shot-import.ts`).
  - SQL schema migrations (`factory/infra/migrations/*`).
  - Pre-built runtime bundles by hand. (If TS types exported to runtime change, run `pnpm --filter @coday/factory build` to rebuild `factory/runtime/`).
  - `agentos/`.

---

## 2. Technical Design & Implementation Details

### 2.1 Configuration (`loadConfig`)
In `factory/dashboard/composition-root.mjs`:
Update `loadConfig(env = process.env)` to extract:
```javascript
const persistenceMode = (env.FACTORY_PERSISTENCE ?? 'fs').toLowerCase() === 'sql' ? 'sql' : 'fs'
const shadowReadEnabled = persistenceMode === 'fs' && (env.FACTORY_PERSISTENCE_SHADOW ?? 'false').toLowerCase() === 'true'
```
Add `persistenceMode` and `shadowReadEnabled` (plus raw `env` or db options if passed down) to the return object of `loadConfig`.

### 2.2 Store & Repository Wiring in `createStores(config, options)`
In `composition-root.mjs`:
`createStores(config, options = {})` accepts an optional options parameter (e.g. `{ sqlClient }` for testing / custom client injection).

#### A. When `persistenceMode === 'sql'`:
Initialize PostgreSQL repositories using either `options.sqlClient` or `createPgPoolClient(resolveSqlDatabaseConfig(process.env))` (loaded dynamically from `runtime/factory-operational.mjs` or adapters).
Wire the store interfaces/wrappers expected by `createApplication` (e.g., `WorkflowProjectionStore`, `WorkflowEvidenceStore`, `AgentStepResultStore`, `WorkflowHumanInteractionStore`, `WorkUnitEnvironmentStore`, `DeliveryStore`, `DeliveryEvidenceStore`, `WorkflowResumeDispatchStore`) to delegate directly to the SQL repositories (`SqlWorkflowInstanceRepository`, `SqlWorkflowEvidenceRepository`, `SqlAgentStepResultRepository`, `SqlWorkflowHumanInteractionRepository`, `SqlWorkEnvironmentRepository`, `SqlDeliveryRepository`, etc.).
Ensure **0 writes** occur on filesystem directories when in SQL mode.

#### B. When `persistenceMode === 'fs'` and `shadowReadEnabled === true`:
Wrap filesystem stores/repositories with shadow-read proxies/decorators.
On read operations (`read`, `get`, `list`, `findBy...`):
1. Execute and retrieve authoritative result from the filesystem store (`fsResult`).
2. Safely perform the corresponding read query on the SQL repository (`sqlResult`).
3. Compute canonical hashes for both using `computeCanonicalHash` (from `factory/runtime/factory-operational.mjs` or `storage-kernel`).
4. If `fsHash !== sqlHash`: log a warning (e.g. `console.warn('[SHADOW_READ_DISCREPANCY]', { key/id, fsHash, sqlHash })`).
5. If SQL query or comparison throws an error: safely catch and log warning (`console.warn('[SHADOW_READ_ERROR]', err)`).
6. **ALWAYS** return `fsResult` to the caller without altering response or throwing.

#### C. When `persistenceMode === 'fs'` and `shadowReadEnabled === false`:
Default behavior: instantiate standard filesystem stores as today.

### 2.3 Shadow-Read Proxy Wrapper Design
Create a reusable helper inside `composition-root.mjs` (or a helper module in `factory/dashboard/`):
```javascript
function createShadowReadStore(fsStore, sqlStore, options = {}) {
  // Return proxy or store wrapper intercepting read methods
  // Calls fsStore[method](...args) -> fsResult
  // Non-blocking/safe async call sqlStore[method](...args) -> compare computeCanonicalHash
  // Return fsResult
}
```

### 2.4 Documenting Switch & Rollback Procedure (`factory/PERSISTENCE_SWITCH_ROLLBACK.md`)
Create `factory/PERSISTENCE_SWITCH_ROLLBACK.md` describing:
1. Operational Overview: FS authority vs SQL authority vs Shadow-Read.
2. Step-by-Step Transition Procedure:
   - Run B4-T1 one-shot migration import to seed PostgreSQL from filesystem.
   - Enable Shadow-Read mode: `FACTORY_PERSISTENCE=fs FACTORY_PERSISTENCE_SHADOW=true node factory/dashboard/server.mjs`.
   - Monitor logs for `[SHADOW_READ_DISCREPANCY]`.
   - Switch Writer Unique to SQL: `FACTORY_PERSISTENCE=sql node factory/dashboard/server.mjs`.
   - Verify filesystem receives 0 new writes.
3. Rollback Procedure:
   - Emergency revert: set `FACTORY_PERSISTENCE=fs` and restart dashboard server.
   - Restores filesystem read/write authority immediately.
4. Containerized PostgreSQL Verification Commands:
   - Using `docker-compose -f factory/infra/docker-compose.yml up -d`.
   - Setting `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`.

---

## 3. Detailed Step-by-Step Action Plan

### Step 1: Update `loadConfig` in `factory/dashboard/composition-root.mjs`
- Parse `FACTORY_PERSISTENCE` (`'fs'` | `'sql'`), defaulting to `'fs'`.
- Parse `FACTORY_PERSISTENCE_SHADOW` (`'true'` | `'false'`), defaulting to `'false'`.
- Return `persistenceMode` and `shadowReadEnabled` in `config`.

### Step 2: Implement Adapter & Shadow Selection in `createStores`
- Update `createStores(config, options = {})`:
  - If `config.persistenceMode === 'sql'`:
    - Obtain SQL client (`options.sqlClient` or `await createPgPoolClient()`).
    - Construct SQL repositories.
    - Adapt/wrap SQL repositories to fit dashboard store interfaces if needed.
  - If `config.shadowReadEnabled === true`:
    - Obtain SQL client and SQL repositories.
    - Wrap filesystem stores with shadow read logic comparing `computeCanonicalHash`.
  - Return stores matching expected interface.

### Step 3: Write Offline Unit/Integration Test Suite
- Create `factory/tests/test-persistence-shadow-and-switch.mjs`.
- Use `createInMemorySqlClient()` from `./support/in-memory-sql-client.mjs` and temporary filesystem directories (`mkdtemp`).
- Test Cases to implement:
  1. **Default State**: `FACTORY_PERSISTENCE=fs` (shadow disabled) operates purely on filesystem.
  2. **Shadow-Read Mode Discrepancy Detection**:
     - Write data to FS. Write different/divergent data to SQL.
     - Call read method on store.
     - Verify: Served response equals FS data; discrepancy warning is logged; no exception thrown.
  3. **Shadow-Read Mode Error Handling**:
     - Inject throwing client into SQL side.
     - Call read method.
     - Verify: FS response returned cleanly without error.
  4. **SQL Writer Mode**:
     - `FACTORY_PERSISTENCE=sql`.
     - Execute write/read operations.
     - Verify: Writes land in SQL client; Filesystem directory receives 0 new files/writes.
  5. **Rollback Verification**:
     - Revert env to `FACTORY_PERSISTENCE=fs`.
     - Verify: FS stores are re-activated as single authority.

### Step 4: Add Documentation (`factory/PERSISTENCE_SWITCH_ROLLBACK.md`)
- Create markdown file with step-by-step instructions and docker-compose commands.

### Step 5: Verification & Quality Gate Check
- Run `node factory/tests/test-persistence-shadow-and-switch.mjs`.
- Run `node factory/tests/test-composition-root-source.mjs` (update source guards if store instantiation counts/signatures change).
- Run project-wide test checks (`pnpm nx affected -t test ...`).

---

## 4. Verification & Validation Commands

```bash
# 1. Run the newly created persistence shadow & switch test suite
node factory/tests/test-persistence-shadow-and-switch.mjs

# 2. Run source guard for composition root
node factory/tests/test-composition-root-source.mjs

# 3. Run full test suite
pnpm test
```
