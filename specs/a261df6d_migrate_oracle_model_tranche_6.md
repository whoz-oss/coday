# Plan: Refactor and Migrate Factory Oracle Model (Tranche 6) to TypeScript

## 1. Overview & Goal

Migrate the legacy JavaScript Oracle model (`tranche 6`) into TypeScript under `factory/src/`.
Replace legacy `.mjs` implementations in `factory/lib/oracle*.mjs` with thin ESM re-export shims delegating to `factory/runtime/factory-operational.mjs`.
Re-bundle the runtime operational bundle using `node factory/toolchain/build.mjs`, update tests, and verify zero regressions.

**Strict Constraint**: Work strictly inside `factory/src/`, `factory/lib/`, `factory/entrypoints/`, `factory/toolchain/`, `factory/tests/`, and `specs/`. Do NOT touch `agentos/**` or non-factory components.

---

## 2. Architecture & Layering Strategy

According to `factory/DEPENDENCY_MATRIX.md` and `factory/ARCHITECTURE.md`:
- `factory/src/domain/oracle/`: Pure domain logic only! **ZERO** Node `fs`, process execution, HTTP, AgentOS, or Git CLI subprocess calls.
- `factory/src/application/oracle/`: Application logic orchestration. Coordinates command building, process execution, baseline execution, diagnostic extraction, and classification.
- Filesystem & subprocess dependencies (`fs`, `child_process`, `exec/spawn`, `git`) belong in application/adapters/ports layers, NOT pure domain modules.

### Detailed Layering Plan for Oracle Components:

#### A. Pure Domain Layer (`factory/src/domain/oracle/`)

1. **`factory/src/domain/oracle/oracle.ts`**:
   - Pure functions and types for task outcome counting and diff evaluation.
   - `countTaskOutcomes(output: string)`: Parses Gradle & Nx task output to count outcomes (upToDate, fromCache, skipped, executed, summary details, countMismatch). Pure string processing, no I/O or side effects.
   - `contentFingerprint` and diff calculation interfaces/types for Git snapshots. (Note: `snapshotDiff` / `diffSince` invoke git CLI or read content — pure diff logic or content hash mapping belongs in pure functions/types, while git execution wrappers belong in application/adapter layer if needed, or helper execution logic in application).

2. **`factory/src/domain/oracle/oracle-definition.ts`**:
   - Pure domain models, schema validation (`validateOracleDefinition`), canonical serialization, hashing (`hashOracleDefinition`), and definition registry logic (`OracleDefinitionRegistry`).
   - Define TypeScript interfaces `OracleDefinition`, `OracleSuccessCondition`, `OracleApplicableCondition`.
   - `OracleDefinitionRegistry`: accepts a filesystem reader abstraction or file dictionary if pure domain, or pure registry class. (In legacy code, `OracleDefinitionRegistry` reads JSON files; initialize can take a file-reading function or port, or `OracleDefinitionRegistry` can be in pure domain with dependency injection for reading files).

#### B. Application / Adapter Layer (`factory/src/application/oracle/`)

3. **`factory/src/application/oracle/oracle-command.ts`**:
   - Oracle command building logic: `resolveOwnerProjects`, `resolveBuildHosts`, `buildOracleCommand`.
   - Utilizes Node `fs` (`existsSync`, `readFileSync`) and `path` to discover Nx `project.json` files and build target commands (`run-many`, `--projects`, `--skip-nx-cache`).
   - Export types such as `NoHostResult`.

4. **`factory/src/application/oracle/oracle-executor.ts`**:
   - Process execution and classification logic: `runCommand`, `executeOracle`, `classifyOracleExecution`, `validateOracleRoot`, `oracleRootIdentity`, `oracleArtifact`.
   - Uses `node:child_process` (`spawnSync`, `spawn`), `node:crypto`, `node:fs/promises`.
   - Integrates `countTaskOutcomes` from domain.

5. **`factory/src/application/oracle/oracle-baseline.ts`**:
   - Baseline execution, diagnostic extraction, normalization, classification, and quarantine record creation.
   - Functions: `stripAnsi`, `normalizeDiagnosticLine`, `extractOracleDiagnostics`, `isInfrastructureIdentity`, `runBaselineOracle`, `classifyOracleResult`, `buildQuarantineRecord`.
   - Types: `BaselineOracleResult`, `OracleClassification`, `OracleClassificationResult`.
   - Uses `runCommand` / `countTaskOutcomes` / `buildOracleCommand` / `resolveOwnerProjects`.

---

## 3. Step-by-Step Implementation Plan

### Step 1: Create TypeScript Domain & Application Source Files

1. **`factory/src/domain/oracle/oracle.ts`**:
   - Port `countTaskOutcomes` logic from `factory/lib/oracle.mjs`.
   - Define type `TaskOutcomesCountResult`.
   - Pure functions, regex ANSI stripping, Gradle & Nx task/summary line parsing.

2. **`factory/src/domain/oracle/oracle-definition.ts`**:
   - Define interfaces `OracleDefinition`, `OracleSuccessRule`, `OracleApplicableRule`.
   - Port `validateOracleDefinition`, `canonical`, `hashOracleDefinition`.
   - Implement `OracleDefinitionRegistry` with typed methods.

3. **`factory/src/application/oracle/oracle-command.ts`**:
   - Port `resolveBuildHosts`, `resolveOwnerProjects`, `extractTarget`, `buildOracleCommand`.
   - Define `NoHostResult` type.
   - Use `node:fs` and `node:path`.

4. **`factory/src/application/oracle/oracle-executor.ts`**:
   - Port `truncate`, `runCommand`, `snapshotDiff`, `diffSince` (or place snapshotDiff/diffSince here / in domain as appropriate), `classifyOracleExecution`, `validateOracleRoot`, `oracleRootIdentity`, `executeOracle`, `oracleArtifact`.
   - Export all required functions and types.

5. **`factory/src/application/oracle/oracle-baseline.ts`**:
   - Port diagnostic normalization (`stripAnsi`, `isNoiseLine`, `normalizeDiagnosticLine`, `extractOracleDiagnostics`).
   - Port `isInfrastructureIdentity`, `runBaselineOracle`, `classifyOracleResult`, `buildQuarantineRecord`.
   - Explicitly import `runCommand` and `countTaskOutcomes` from executor/domain, and `buildOracleCommand` / `resolveOwnerProjects` from `oracle-command.ts`.
   - Note on `extractTypeDiagnostics` / `extractTestDiagnostics`: Check if `us-loop.mjs` is imported or if inline fallback line selection is used / ported cleanly. (Ensure no broken imports to legacy mjs if possible, or support string output parsing).

### Step 2: Update Entrypoint & Build Operational Bundle

1. **`factory/src/entrypoints/factory-operational.ts`**:
   - Export all new oracle exports from domain and application:
     ```ts
     export * from '../domain/oracle/oracle.js'
     export * from '../domain/oracle/oracle-definition.js'
     export * from '../application/oracle/oracle-command.js'
     export * from '../application/oracle/oracle-executor.js'
     export * from '../application/oracle/oracle-baseline.js'
     ```

2. **Run Operational Build**:
   - Command: `node factory/toolchain/build.mjs`
   - Verify `factory/runtime/factory-operational.mjs` is generated without build errors.

### Step 3: Replace Legacy `factory/lib/oracle*.mjs` with Re-export Shims

Replace the contents of the following 5 files with thin re-export shims from `../runtime/factory-operational.mjs` matching the Tranche 5 pattern:

1. **`factory/lib/oracle.mjs`**:
   ```js
   export {
     countTaskOutcomes,
     runCommand,
     snapshotDiff,
     diffSince,
   } from '../runtime/factory-operational.mjs'
   ```
2. **`factory/lib/oracle-definition.mjs`**:
   ```js
   export {
     validateOracleDefinition,
     hashOracleDefinition,
     OracleDefinitionRegistry,
   } from '../runtime/factory-operational.mjs'
   ```
3. **`factory/lib/oracle-command.mjs`**:
   ```js
   export {
     resolveBuildHosts,
     resolveOwnerProjects,
     buildOracleCommand,
   } from '../runtime/factory-operational.mjs'
   ```
4. **`factory/lib/oracle-executor.mjs`**:
   ```js
   export {
     classifyOracleExecution,
     validateOracleRoot,
     oracleRootIdentity,
     executeOracle,
     oracleArtifact,
   } from '../runtime/factory-operational.mjs'
   ```
5. **`factory/lib/oracle-baseline.mjs`**:
   ```js
   export {
     normalizeDiagnosticLine,
     extractOracleDiagnostics,
     isInfrastructureIdentity,
     runBaselineOracle,
     classifyOracleResult,
     buildQuarantineRecord,
   } from '../runtime/factory-operational.mjs'
   ```

### Step 4: Update Operational Verification Tests

1. **`factory/tests/typescript-factory-operational.mjs`**:
   - Add assertion lists for Oracle exports:
     - `expectedOracleExports` (`countTaskOutcomes`, `runCommand`, `snapshotDiff`, `diffSince`, `validateOracleDefinition`, `hashOracleDefinition`, `OracleDefinitionRegistry`, `resolveBuildHosts`, `resolveOwnerProjects`, `buildOracleCommand`, `classifyOracleExecution`, `validateOracleRoot`, `oracleRootIdentity`, `executeOracle`, `oracleArtifact`, `normalizeDiagnosticLine`, `extractOracleDiagnostics`, `isInfrastructureIdentity`, `runBaselineOracle`, `classifyOracleResult`, `buildQuarantineRecord`).
   - Check module exports in `factory-operational.mjs`.
   - Verify facade identity matches for:
     - `factory/lib/oracle.mjs`
     - `factory/lib/oracle-definition.mjs`
     - `factory/lib/oracle-command.mjs`
     - `factory/lib/oracle-executor.mjs`
     - `factory/lib/oracle-baseline.mjs`
   - Exercise basic functions (e.g. `countTaskOutcomes('> Task :compileJava UP-TO-DATE')`, `validateOracleDefinition(...)`, `normalizeDiagnosticLine(...)`) to ensure functionality.
   - Add input source checks against `metafile.inputs` for new TypeScript files (`src/domain/oracle/...`, `src/application/oracle/...`).

### Step 5: Verification & Quality Checks

1. Build bundle:
   `node factory/toolchain/build.mjs`
2. Run operational verification test:
   `node factory/tests/typescript-factory-operational.mjs`
3. Run project tests:
   `pnpm nx test factory` (or `pnpm test`) if applicable.
4. Verify git status and format code cleanly.

### Step 6: Commit

Commit all changes with the exact commit subject:
`refactor(factory): migrate oracle model to TypeScript domain (tranche 6)`

---

## 4. Verification Checklist for Next Agent (Builder)

- [ ] All new files under `factory/src/domain/oracle/` have zero dependencies on Node `fs`, HTTP, child_process, or Git CLI.
- [ ] Application/adapter code placed strictly under `factory/src/application/oracle/`.
- [ ] Operational bundle rebuilt via `node factory/toolchain/build.mjs`.
- [ ] `node factory/tests/typescript-factory-operational.mjs` passes with 0 errors.
- [ ] Re-export shims in `factory/lib/oracle*.mjs` delegate directly to `../runtime/factory-operational.mjs`.
- [ ] Conventional commit completed.
