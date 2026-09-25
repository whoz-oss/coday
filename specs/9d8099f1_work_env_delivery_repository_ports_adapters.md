# Plan: Persistence Ports and Filesystem Adapters for WorkEnvironment and Delivery (Phase 6 Tranche 2 - Part 2)

## Context & Pattern

This work introduces persistence repository ports, filesystem adapters, facade helpers, bundle exports, and tests for **Group 2**:
1. `WorkEnvironmentRepository` / `FilesystemWorkEnvironmentRepository`
2. `DeliveryRepository` / `FilesystemDeliveryRepository`

This follows the established hexagonal persistence architecture from earlier Group 1 migrations (commits `b5735728` and `884fc216`, spec `12503e35_repository_ports_adapters_group1.md`).

### Key Guidelines & Constraints
- Scope: `factory/**` ONLY. Do NOT touch `agentos/**`.
- Reuse existing domain models (`WorkUnitEnvironment`, `WorkUnitEnvironmentState`, `DeliverySnapshot`, `DeliveryJournalRecord`, `DeliveryOperationProjection`, etc.) and store classes (`WorkUnitEnvironmentStore`, `DeliveryStore`). Do NOT rewrite storage kernel or persistent file formats.
- Maintain legacy `.mjs` facade compatibility by exporting `createWorkEnvironmentRepository(dataRoot, options)` from `factory/lib/work-unit-environment-store.mjs` and `createDeliveryRepository(dataRoot, options)` from `factory/lib/delivery-store.mjs`.
- Export ports and adapters in barrels (`factory/src/ports/persistence/index.ts`, `factory/src/adapters/persistence/index.ts`) and through the operational entrypoint (`factory/src/entrypoints/factory-operational.ts`).
- Verify via offline unit/integration test suite: `node factory/tests/test-repository-ports-adapters.mjs` and `node factory/tests/typescript-factory-operational.mjs`.

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Create `WorkEnvironmentRepository` Port
**File to create:** `factory/src/ports/persistence/work-environment-repository.ts`

**Imports to use:**
- `WorkUnitEnvironmentPaths`, `EnvironmentSnapshot`, `StoreWriteResult` from `../../adapters/persistence/work-unit-environment-store.js` (or domain/environment types as appropriate)
- `ValidationFailure`, `WorkUnitEnvironment`, `WorkUnitEnvironmentState` from `../../domain/environment/work-unit-environment.js`

**Interface definition:**
```typescript
export interface WorkEnvironmentRepository {
  paths(namespaceId: string, environmentId: string): WorkUnitEnvironmentPaths
  read(namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null>
  list(namespaceId: string, filter?: { states?: readonly WorkUnitEnvironmentState[] }): Promise<EnvironmentSnapshot[]>
  reserve(environment: unknown): Promise<StoreWriteResult | ValidationFailure>
  transition(
    namespaceId: string,
    environmentId: string,
    next: WorkUnitEnvironment,
    options?: { expectedRevision?: number; errorCode?: string }
  ): Promise<StoreWriteResult | ValidationFailure>
}
```

---

### Step 2: Create `FilesystemWorkEnvironmentRepository` Adapter
**File to create:** `factory/src/adapters/persistence/filesystem-work-environment-repository.ts`

**Interface dependency & Adapter implementation:**
Define `WorkUnitEnvironmentStoreLike` structural interface (matching `WorkUnitEnvironmentStore` methods used by the port).
Implement `FilesystemWorkEnvironmentRepository implements WorkEnvironmentRepository` delegating directly to `store`:
- `paths(namespaceId: string, environmentId: string)` -> `this.store.paths(namespaceId, environmentId)`
- `read(namespaceId: string, environmentId: string)` -> `this.store.read(namespaceId, environmentId)`
- `list(namespaceId: string, filter?: { states?: readonly WorkUnitEnvironmentState[] })` -> `this.store.list(namespaceId, filter)`
- `reserve(environment: unknown)` -> `this.store.reserve(environment)`
- `transition(namespaceId: string, environmentId: string, next: WorkUnitEnvironment, options?: { expectedRevision?: number; errorCode?: string })` -> `this.store.transition(namespaceId, environmentId, next, options)`

**Helper function:**
```typescript
export function createFilesystemWorkEnvironmentRepository(
  store: WorkUnitEnvironmentStoreLike
): FilesystemWorkEnvironmentRepository {
  return new FilesystemWorkEnvironmentRepository(store)
}
```

---

### Step 3: Create `DeliveryRepository` Port
**File to create:** `factory/src/ports/persistence/delivery-repository.ts`

**Imports to use:**
- `DeliverySnapshot`, `DeliveryJournalRecord`, `DeliveryOperationProjection`, `DeliveryStoreWriteResult`, `DeliveryStorePromoteInput`, `DeliveryStoreRollbackRequestInput`, `DeliveryStoreRollbackApprovalInput`, `DeliveryStoreOperationInput`, `DeliveryOperationTransitionInput` from `../../adapters/persistence/delivery-store.js`
- `DeliveryOperationObservation` from `../../domain/delivery/delivery-operation-definition.js`

**Interface definition:**
```typescript
export interface DeliveryRepository {
  read(namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null>
  create(input: Record<string, unknown>): Promise<DeliveryStoreWriteResult>
  promote(input: DeliveryStorePromoteInput): Promise<DeliveryStoreWriteResult>
  readWithOperations(
    namespaceId: string,
    deliveryId: string
  ): Promise<(DeliverySnapshot & { deliveryOperations: DeliveryJournalRecord[]; rollbackRequests: DeliveryJournalRecord[] }) | null>
  inspectDeliveryOperations(namespaceId: string, deliveryId: string): Promise<DeliveryOperationProjection>
  createRollbackRequest(input: DeliveryStoreRollbackRequestInput): Promise<DeliveryStoreWriteResult>
  approveRollbackRequest(
    namespaceId: string,
    deliveryId: string,
    rollbackRequestId: string,
    approval: DeliveryStoreRollbackApprovalInput
  ): Promise<DeliveryStoreWriteResult>
  createDeliveryOperation(input: DeliveryStoreOperationInput): Promise<DeliveryStoreWriteResult>
  recordDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    transition: DeliveryOperationTransitionInput,
    options?: { inspectedObservation?: DeliveryOperationObservation }
  ): Promise<DeliveryStoreWriteResult>
  startDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    adapterCorrelation: unknown
  ): Promise<DeliveryStoreWriteResult>
  reconcileDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    observation: DeliveryOperationObservation
  ): Promise<DeliveryStoreWriteResult>
  hasIndeterminateOperation(namespaceId: string, deliveryId: string): Promise<boolean>
  updateSnapshot(
    namespaceId: string,
    deliveryId: string,
    patch: Record<string, unknown>,
    operationInput: { kind: string; idempotencyKey: string; facts?: unknown }
  ): Promise<DeliveryStoreWriteResult>
}
```

---

### Step 4: Create `FilesystemDeliveryRepository` Adapter
**File to create:** `factory/src/adapters/persistence/filesystem-delivery-repository.ts`

**Interface dependency & Adapter implementation:**
Define `DeliveryStoreLike` structural interface.
Implement `FilesystemDeliveryRepository implements DeliveryRepository` delegating directly to `store`:
- Method signatures mapping 1-to-1 to `DeliveryStore` methods: `read`, `create`, `promote`, `readWithOperations`, `inspectDeliveryOperations`, `createRollbackRequest`, `approveRollbackRequest`, `createDeliveryOperation`, `recordDeliveryOperation`, `startDeliveryOperation`, `reconcileDeliveryOperation`, `hasIndeterminateOperation`, `updateSnapshot`.

**Helper function:**
```typescript
export function createFilesystemDeliveryRepository(
  store: DeliveryStoreLike
): FilesystemDeliveryRepository {
  return new FilesystemDeliveryRepository(store)
}
```

---

### Step 5: Export Ports and Adapters via Barrels and Operational Entrypoint

1. **`factory/src/ports/persistence/index.ts`**:
   - Export `WorkEnvironmentRepository` and types
   - Export `DeliveryRepository` and types

2. **`factory/src/adapters/persistence/index.ts`**:
   - Export `FilesystemWorkEnvironmentRepository`, `createFilesystemWorkEnvironmentRepository`, `type WorkUnitEnvironmentStoreLike`
   - Export `FilesystemDeliveryRepository`, `createFilesystemDeliveryRepository`, `type DeliveryStoreLike`

3. **`factory/src/entrypoints/factory-operational.ts`**:
   - Verify barrel re-exports `export * from '../ports/persistence/index.js'` and `export * from '../adapters/persistence/index.ts'` are intact (already present, verify compilation/bundle inclusion).

---

### Step 6: Update Compatibility Facades (`.mjs`)

1. **`factory/lib/work-unit-environment-store.mjs`**:
   - Re-export `FilesystemWorkEnvironmentRepository`, `createFilesystemWorkEnvironmentRepository` from `../runtime/factory-operational.mjs`.
   - Export `createWorkEnvironmentRepository(dataRoot, options)` function:
     ```javascript
     export function createWorkEnvironmentRepository(dataRoot, options) {
       return createFilesystemWorkEnvironmentRepository(new WorkUnitEnvironmentStore(dataRoot, options))
     }
     ```

2. **`factory/lib/delivery-store.mjs`**:
   - Re-export `FilesystemDeliveryRepository`, `createFilesystemDeliveryRepository` from `../runtime/factory-operational.mjs`.
   - Export `createDeliveryRepository(dataRoot, options)` function:
     ```javascript
     export function createDeliveryRepository(dataRoot, options) {
       return createFilesystemDeliveryRepository(new DeliveryStore(dataRoot, options))
     }
     ```

---

### Step 7: Update and Add Tests in `factory/tests/test-repository-ports-adapters.mjs`

Add test scenarios for both repositories in `factory/tests/test-repository-ports-adapters.mjs`:

1. **WorkEnvironment scenario**:
   - Test creating a `WorkEnvironmentRepository` using `createWorkEnvironmentRepository(root)`.
   - Verify `instanceof FilesystemWorkEnvironmentRepository`.
   - Test `paths()`, `reserve()` an environment snapshot, `read()`, `list()` by namespace/filter, and `transition()` to next state.

2. **Delivery scenario**:
   - Test creating a `DeliveryRepository` using `createDeliveryRepository(root)`.
   - Verify `instanceof FilesystemDeliveryRepository`.
   - Test `create()`, `read()`, `promote()`, `createDeliveryOperation()`, `startDeliveryOperation()`, `readWithOperations()`, `inspectDeliveryOperations()`, and `hasIndeterminateOperation()`.

---

## Verification Plan

1. Run repository ports/adapters tests:
   ```bash
   node factory/tests/test-repository-ports-adapters.mjs
   ```
   *Expected output: All scenarios pass (10 passed, 0 failed).*

2. Run TypeScript operational contract verification:
   ```bash
   node factory/tests/typescript-factory-operational.mjs
   ```
   *Expected output: Passes without error.*

3. Run full project tests:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```
   *Expected output: Success.*
