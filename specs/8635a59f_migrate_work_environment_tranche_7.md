# Plan: Migrate Tranche 7 — WORK ENVIRONMENT to TypeScript Domain and Application Layers

## Task Description
Migrate Tranche 7 (Work Unit Environment) of the Factory subsystem from legacy `.mjs` files (`factory/lib/work-unit-environment*.mjs`) to TypeScript domain, application, and adapter layers under `factory/src/`. Export all public functions, classes, and types through `factory/src/entrypoints/factory-operational.ts`, rebuild the operational bundle (`factory/runtime/factory-operational.mjs`), replace legacy `.mjs` files with thin stateless re-export shims, and verify with tests.

## Architecture & Module Structure

Following the exact pattern established in Tranche 6 (Oracle, commit 4f4e64c8):

1. **Domain Layer** (`factory/src/domain/environment/work-unit-environment.ts`):
   - Types and interfaces: `WorkUnitEnvironment`, `WorkUnitEnvironmentInput`, `WorkUnitEnvironmentState`, `WorkUnitEnvironmentErrorCode`, `ValidationResult`.
   - Constants: `WORK_UNIT_ENVIRONMENT_STATES`, `WORK_UNIT_ENVIRONMENT_ERROR_CODES`.
   - Pure validation functions (no `node:fs`, git, or AgentOS dependencies):
     - `validateNamespaceId`
     - `validateCanonicalAbsolutePath`
     - `validateGitRef`
     - `validateIsoInstant`
     - `validateWorkUnitEnvironment`

2. **Application & Adapter Layers**:
   - `factory/src/adapters/persistence/work-unit-environment-store.ts`:
     - Constants: `ENVIRONMENT_STORE_ERROR_CODES`.
     - Error class: `WorkUnitEnvironmentStoreError`.
     - Class: `WorkUnitEnvironmentStore` (implements file-backed storage, locking, pending/journal recovery, directory sandboxing, atomic JSON persistence using `node:crypto`, `node:fs/promises`, `node:path`).
   - `factory/src/application/environment/work-unit-environment-service.ts`:
     - Class: `WorkUnitEnvironmentService` (implements worktree provisioning, binding parent cases, inspecting, setting state, removal, recovery candidate listing, locking, error classification).
   - `factory/src/application/environment/work-unit-environment-controller.ts`:
     - Helper functions: `publicEnvironment`, `error`.
     - Class: `WorkUnitEnvironmentController` (implements HTTP control-plane methods `initialize`, `provision`, `get`, `reconcile`, `release`).
     - Function: `handleWorkUnitEnvironmentRequest` (HTTP request dispatcher handling `/api/factory/workflows/:id/environment/*`).

3. **Entrypoint Export** (`factory/src/entrypoints/factory-operational.ts`):
   - Export all domain types, error codes, state constants, and validation functions from `../domain/environment/work-unit-environment.js`.
   - Export store error codes, store error class, and store class from `../adapters/persistence/work-unit-environment-store.js`.
   - Export service class from `../application/environment/work-unit-environment-service.js`.
   - Export controller class and handler function from `../application/environment/work-unit-environment-controller.js`.

4. **Runtime Bundle & Legacy Shims**:
   - Run `cd factory/toolchain && npm run build` to update `factory/runtime/factory-operational.mjs`.
   - Update `factory/lib/git-worktree.mjs` to import `validateGitRef` from `../runtime/factory-operational.mjs` (or maintain its import from `factory/lib/work-unit-environment.mjs` since the shim re-exports it).
   - Replace existing legacy `.mjs` files with thin stateless re-export shims:
     - `factory/lib/work-unit-environment.mjs` -> re-exports from `../runtime/factory-operational.mjs`
     - `factory/lib/work-unit-environment-store.mjs` -> re-exports from `../runtime/factory-operational.mjs`
     - `factory/lib/work-unit-environment-service.mjs` -> re-exports from `../runtime/factory-operational.mjs`
     - `factory/lib/work-unit-environment-controller.mjs` -> re-exports from `../runtime/factory-operational.mjs`

5. **Test Assertions & Verification**:
   - Add unit test assertions in `factory/tests/typescript-factory-operational.mjs`:
     - Verify operational bundle exports all work unit environment functions, classes, and constants.
     - Verify `.mjs` facades delegate correctly with strict identity assertions.
     - Check metafile inputs for work unit environment source inclusion.
     - Exercise validation, store, service, and controller contracts.
   - Run `cd factory/toolchain && npm run test:operational`.
   - Run existing legacy tests: `node factory/tests/test-work-unit-environment.mjs`, `node factory/tests/test-work-unit-environment-service.mjs`, `node factory/tests/test-work-unit-environment-phase8.mjs`.

## Detailed Implementation Steps

### Step 1: Create Domain Layer (`factory/src/domain/environment/work-unit-environment.ts`)
- Port pure validation logic and constants from `factory/lib/work-unit-environment.mjs`.
- Ensure exact backward compatibility for returned error structures, validation regexes (`SAFE`, `UUID`, `SHA`, `REF`), and environment descriptor normalization.
- Ensure strict TypeScript types for all exports.

### Step 2: Create Store Adapter (`factory/src/adapters/persistence/work-unit-environment-store.ts`)
- Port storage persistence and recovery logic from `factory/lib/work-unit-environment-store.mjs`.
- Import `validateNamespaceId` and `validateWorkUnitEnvironment` from `../../domain/environment/work-unit-environment.js`.
- Retain exact error codes, error handling, atomic write semantics, pending/journal recovery, fault injection callbacks, and path containment checks.

### Step 3: Create Application Service (`factory/src/application/environment/work-unit-environment-service.ts`)
- Port provisioning, state machine, locking, and recovery logic from `factory/lib/work-unit-environment-service.mjs`.
- Ensure exact error machine code extraction, identity matching, fault points, and Git worktree integration signatures.

### Step 4: Create Application Controller & HTTP Handler (`factory/src/application/environment/work-unit-environment-controller.ts`)
- Port controller orchestration and request handler from `factory/lib/work-unit-environment-controller.mjs`.
- Maintain exact HTTP path matching, trust identity verification, error codes, HTTP status codes, and JSON response shapes.

### Step 5: Update Operational Entrypoint (`factory/src/entrypoints/factory-operational.ts`)
- Add re-exports for work unit environment domain, store, service, and controller modules.

### Step 6: Build Bundle & Convert Legacy Files to Shims
- Execute `cd factory/toolchain && npm run build`.
- Update legacy `.mjs` files (`work-unit-environment.mjs`, `work-unit-environment-store.mjs`, `work-unit-environment-service.mjs`, `work-unit-environment-controller.mjs`) to re-export everything from `../runtime/factory-operational.mjs`.

### Step 7: Update Toolchain Test Suite & Verify
- Add assertion blocks in `factory/tests/typescript-factory-operational.mjs` testing bundle exports, shim identity, metafile tracking, and core operations.
- Execute `cd factory/toolchain && npm run test:operational`.
- Run legacy test scripts to ensure 100% regression avoidance.

## Verification Checklist
- `cd factory/toolchain && npm run build` compiles without errors.
- `cd factory/toolchain && npm run test:operational` passes completely.
- `node factory/tests/test-work-unit-environment.mjs` passes.
- `node factory/tests/test-work-unit-environment-service.mjs` passes.
- `node factory/tests/test-work-unit-environment-phase8.mjs` passes.
- All legacy `.mjs` files are stateless re-exports.
