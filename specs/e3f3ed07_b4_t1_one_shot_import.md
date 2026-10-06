# Implementation Plan: B4-T1 One-Shot Import Module, Entrypoint, Verification, and Tests

## Overview
Milestone B4-T1 implements a one-shot import mechanism to populate PostgreSQL from the filesystem repository state for a shared multi-user Factory architecture. This task strictly reads existing filesystem aggregates (across 8 persistence contexts) and imports them into PostgreSQL using SQL repositories wrapped in transactional units of work. It performs post-import verification (count equality + aggregate canonical hash equality) and guarantees complete idempotence when re-run against an already populated database.

## Architecture & Scope Boundaries

### Strict Constraints
1. **DO NOT modify existing filesystem or SQL repository adapters** (`factory/src/adapters/persistence/...`).
2. **DO NOT touch composition-root** (`composition-root.ts` / `composition-root.mjs`).
3. **DO NOT change current runtime server configuration or writers** (server remains on filesystem).
4. **DO NOT touch DB migration scripts or `agentos/`**.
5. **Ensure all tests pass** (`node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/test-persistence-import.mjs`, `node factory/tests/typescript-factory-operational.mjs`, `node factory/toolchain/build.mjs`).

---

## Targeted Persistence Contexts (8 Contexts) & Aggregate Details

| # | Persistence Context | FS Discovery & Read Method | Aggregate Keys | SQL Upsert / Idempotent Writing Mechanism |
|---|---|---|---|---|
| 1 | `workflow-definition` | `WorkflowDefinitionRegistry(root).initialize()` -> `list()` | (`workflowType`, `version`) | Direct `INSERT INTO workflow_definitions (...) VALUES (...) ON CONFLICT (organization_id, workflow_type, version) DO UPDATE SET definition_hash = EXCLUDED.definition_hash, definition_json = EXCLUDED.definition_json` via `withTransaction(tx)` |
| 2 | `workflow-instance` | Scan `{dataRoot}/workflows/*` for namespaces & instances -> `read(ns, wfId)` | (`namespaceId`, `workflowId`) | `SqlWorkflowInstanceRepository.create` uses `ON CONFLICT DO NOTHING`. Check if existing row matches creation_command_hash; if same, safe no-op |
| 3 | `workflow-evidence` | Scan `{dataRoot}/evidence/*` -> `readJsonLines` or `WorkflowEvidenceStore.list(ns, storageId)` | (`namespaceId`, `evidenceId`) | Direct `INSERT INTO workflow_evidence (...) VALUES (...) ON CONFLICT (organization_id, workstream_id, namespace_id, evidence_id) DO NOTHING` via `withTransaction(tx)` |
| 4 | `workflow-human-interaction` | Scan `{dataRoot}/interaction/*` -> `readJsonLines` or `WorkflowHumanInteractionStore.list(ns, storageId)` | (`namespaceId`, `interactionId`) | Direct `INSERT INTO workflow_human_interactions (...) VALUES (...) ON CONFLICT (organization_id, workstream_id, namespace_id, interaction_id) DO NOTHING` via `withTransaction(tx)` |
| 5 | `agent-step-attempt` | Scan `{dataRoot}/attempts/*` -> `AgentStepAttemptStore.list(ns, storageId)` | (`namespaceId`, `attemptId`) | `SqlAgentStepAttemptRepository.append` or direct `INSERT INTO agent_step_attempts (...) VALUES (...) ON CONFLICT (organization_id, workstream_id, namespace_id, step_id, attempt_id) DO UPDATE SET status=EXCLUDED.status, revision=EXCLUDED.revision, payload=EXCLUDED.payload` + `agent_step_attempt_events` |
| 6 | `agent-step-result` | Scan `{dataRoot}/results/*` -> `AgentStepResultStore.list(ns, storageId)` | (`namespaceId`, `resultId` / `eventId`) | `SqlAgentStepResultRepository.record` or direct `INSERT INTO agent_step_result_ledger_events (...) VALUES (...) ON CONFLICT (organization_id, workstream_id, event_id) DO NOTHING` |
| 7 | `oracle-execution` | `OracleDefinitionRegistry(root).initialize()` -> `list()` | (`oracleId`, `version` or `executionId`) | Direct `INSERT INTO oracle_definitions (...) VALUES (...) ON CONFLICT (organization_id, oracle_id, version) DO UPDATE SET definition_hash=EXCLUDED.definition_hash, definition_json=EXCLUDED.definition_json` |
| 8 | `work-environment` | `WorkUnitEnvironmentStore(root).list(ns)` -> `read(ns, envId)` | (`namespaceId`, `environmentId`) | `SqlWorkEnvironmentRepository` or direct `INSERT INTO work_environments (...) VALUES (...) ON CONFLICT (organization_id, workstream_id, namespace_id, environment_id) DO UPDATE SET status=EXCLUDED.status, revision=EXCLUDED.revision, payload=EXCLUDED.payload` |
| 9 | `delivery` | `DeliveryStore(root).readWithOperations(ns, deliveryId)` | (`namespaceId`, `deliveryId`) | Direct `INSERT INTO delivery_releases (...) VALUES (...) ON CONFLICT (organization_id, workstream_id, namespace_id, delivery_id) DO UPDATE SET status=EXCLUDED.status, revision=EXCLUDED.revision, payload=EXCLUDED.payload` + journals |

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Create the One-Shot Import Module
**File**: `factory/src/adapters/persistence/migration/one-shot-import.ts`

**Responsibilities**:
1. Export configuration types and functions:
   ```ts
   export interface OneShotImportOptions {
     dataRoot: string
     organizationId?: string
     workstreamId?: string
     sqlClient: SqlClient
   }

   export interface ContextVerificationResult {
     context: string
     filesystemCount: number
     sqlCount: number
     ok: boolean
     discrepancies: Array<{
       key: string
       filesystemHash?: string
       sqlHash?: string
       reason: string
     }>
   }

   export interface VerificationReport {
     ok: boolean
     contexts: Record<string, ContextVerificationResult>
     totalFilesystemAggregates: number
     totalSqlAggregates: number
   }

   export async function runOneShotImport(options: OneShotImportOptions): Promise<VerificationReport>
   export async function verifyImport(options: OneShotImportOptions): Promise<VerificationReport>
   ```

2. Implementation Details for `runOneShotImport`:
   - Accept `sqlClient`, `dataRoot`, `organizationId` (default `DEFAULT_ORGANIZATION_ID = 'default'`), `workstreamId` (default `DEFAULT_WORKSTREAM_ID = 'default'`).
   - Discover and load all aggregates from FS:
     * **workflow-definition**: Use `WorkflowDefinitionRegistry` pointing to `${dataRoot}/workflows` (or workflows directory in dataRoot), call `.initialize()` and `.list()`.
     * **workflow-instance**: Discover `{dataRoot}/workflows/*/*` directories; load `projection.json` or `instance.json` via `FilesystemWorkflowInstanceRepository` / `WorkflowProjectionStore`.
     * **workflow-evidence**: Discover `{dataRoot}/evidence/*/*` journals or use `WorkflowEvidenceStore` / `readJsonLines`.
     * **workflow-human-interaction**: Discover `{dataRoot}/interaction/*/*` or `WorkflowHumanInteractionStore`.
     * **agent-step-attempt**: Discover `{dataRoot}/attempts/*/*` or `AgentStepAttemptStore`.
     * **agent-step-result**: Discover `{dataRoot}/results/*/*` or `AgentStepResultStore`.
     * **oracle-execution**: Discover oracles or use `OracleDefinitionRegistry` / `FilesystemOracleExecutionRepository`.
     * **work-environment**: Discover `{dataRoot}/environment/*/*` or `WorkUnitEnvironmentStore`.
     * **delivery**: Discover `{dataRoot}/delivery/*/*` or `DeliveryStore`.
   - Perform idempotent SQL writes wrapped in `withTransaction(options.sqlClient, async (tx) => { ... })`:
     * Execute SQL inserts with `ON CONFLICT DO UPDATE` or `ON CONFLICT DO NOTHING` to ensure re-running the import is fully idempotent and never throws unique constraint collisions.
   - Run `verifyImport` post-import and return the `VerificationReport`.

3. Implementation Details for `verifyImport`:
   - For each context:
     * List all aggregates in FS.
     * List / query all corresponding aggregates in SQL.
     * Calculate `computeCanonicalHash(aggregate)` for each aggregate from FS and SQL using `computeCanonicalHash` from `storage-kernel.ts`.
     * Compare counts and aggregate-by-aggregate hashes.
     * Flag `ok: false` and populate detailed discrepancy items if counts differ or any hash mismatch occurs.

---

### Step 2: Create the Executable CLI Entrypoint
**File**: `factory/src/entrypoints/import-one-shot.ts`

**Responsibilities**:
1. Parse environment variables:
   - `FACTORY_DATA_ROOT` (default to process.cwd() or standard factory data root).
   - `DEFAULT_ORGANIZATION_ID` or `ORGANIZATION_ID` (defaults to 'default').
   - `DEFAULT_WORKSTREAM_ID` or `WORKSTREAM_ID` (defaults to 'default').
   - Database settings resolved via `resolveSqlDatabaseConfig(process.env)`.
2. Instantiate `SqlClient` via `createPgPoolClient(config)`.
3. Call `runOneShotImport({ dataRoot, organizationId, workstreamId, sqlClient })`.
4. Log structured summary output (e.g. `[one-shot-import] Success: ok=true, totalAggregates=...` or discrepancy details).
5. Call `processExit(report.ok ? 0 : 1)`.

---

### Step 3: Update Barrel Exports & Re-exports
**Files**:
- `factory/src/adapters/persistence/index.ts`: Re-export `runOneShotImport`, `verifyImport`, `OneShotImportOptions`, `VerificationReport`.
- `factory/src/entrypoints/factory-operational.ts`: Ensure `runOneShotImport` and verification types are re-exported so they are available in `factory/runtime/factory-operational.mjs`.

---

### Step 4: Add Automated Test Suite
**File**: `factory/tests/test-persistence-import.mjs`

**Test Cases**:
1. **Offline Setup**:
   - Use `createInMemorySqlClient()` from `factory/tests/support/in-memory-sql-client.mjs`.
   - Create a temporary filesystem data root using `mkdtemp`.
2. **Dataset Seeding**:
   - Seed sample aggregates across all 8 contexts on the filesystem using actual store / registry classes.
3. **Execution & Initial Verification**:
   - Run `runOneShotImport(...)`.
   - Assert `report.ok === true`.
   - Assert counts match for all 8 contexts.
   - Assert canonical hashes match for all imported aggregates.
4. **Idempotence Assertion**:
   - Re-run `runOneShotImport(...)` against the same populated database.
   - Assert `report.ok === true`.
   - Assert no duplicate rows or SQL errors occurred.
5. **Discrepancy Flagging**:
   - Inject a discrepancy (e.g., alter or delete an aggregate in SQL or FS).
   - Call `verifyImport(...)`.
   - Assert `report.ok === false`.
   - Assert discrepancy details contain the exact mismatched key/reason.

---

### Step 5: Verification & Bundle Check
1. Run test suite:
   ```bash
   node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/test-persistence-import.mjs
   ```
2. Run build toolchain:
   ```bash
   node factory/toolchain/build.mjs
   ```
3. Run typescript bundle verification:
   ```bash
   node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/typescript-factory-operational.mjs
   ```
4. Run Nx affected tests:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```

---

## Artifacts to Write
- `<context_handoff_dir>/plan.md`
- `specs/e3f3ed07_b4_t1_one_shot_import.md`
