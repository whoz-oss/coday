# Plan: Repository Ports & Filesystem Adapters - Group 1 (AgentStepAttempt, AgentStepResult, OracleExecution)

## Overview
This plan defines the Phase 6 tranche 2 - Part 1 implementation in the `factory` codebase.
Following the hexagonal architecture pattern established in commit `b5735728`, we will create repository port interfaces in `factory/src/ports/persistence/`, filesystem adapter implementations in `factory/src/adapters/persistence/`, barrel exports, entrypoint exports in `factory/src/entrypoints/factory-operational.ts`, `.mjs` facade exports, and comprehensive integration tests in `factory/tests/test-repository-ports-adapters.mjs`.

## Scope
- Directory: `factory/**` ONLY. Do NOT touch `agentos/**` or any other directories.
- Group 1 Bounded Contexts:
  1. `AgentStepAttempt`
  2. `AgentStepResult`
  3. `OracleExecution` (Oracle definitions & baselines / executions)

---

## Technical Details & Specific Architecture

### 1. AgentStepAttempt
- **Port Interface**: `factory/src/ports/persistence/agent-step-attempt-repository.ts`
  - Import types from `../../domain/agent-attempt/agent-step-attempt.js`.
  - Interface `AgentStepAttemptRepository`:
    - `list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]>`
    - `append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt>`
- **Adapter**: `factory/src/adapters/persistence/filesystem-agent-step-attempt-repository.ts`
  - Injected dependency interface `AgentStepAttemptStoreLike`:
    - `list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]>`
    - `append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt>`
  - Class `FilesystemAgentStepAttemptRepository` implementing `AgentStepAttemptRepository` by delegating to `store: AgentStepAttemptStoreLike`.
  - Helper factory `createFilesystemAgentStepAttemptRepository(store: AgentStepAttemptStoreLike)`.
- **Facade Helper in `factory/lib/agent-step-attempt-store.mjs`**:
  - Export `createAgentStepAttemptRepository(dataRoot)`:
    Instantiates `new AgentStepAttemptStore(dataRoot)` and returns `createFilesystemAgentStepAttemptRepository(store)`.
  - Re-export `FilesystemAgentStepAttemptRepository` and `createFilesystemAgentStepAttemptRepository` from `../runtime/factory-operational.mjs`.

### 2. AgentStepResult
- **Port Interface**: `factory/src/ports/persistence/agent-step-result-repository.ts`
  - Import types from `../../domain/agent-attempt/agent-step-result.js`.
  - Interface `AgentStepResultRepository`:
    - `issue(namespaceId: string, storageId: string, identity: AgentStepResultCapabilityIdentity): Promise<AgentStepResultIssueResult>`
    - `submit(token: string, business: unknown, observed?: Partial<AgentStepResultObservedIdentity>): Promise<AgentStepResultSubmitResult>`
    - `getByAttempt(namespaceId: string, storageId: string, attemptId: string): Promise<AgentStepResultSubmitted | null>`
    - `list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]>`
- **Adapter**: `factory/src/adapters/persistence/filesystem-agent-step-result-repository.ts`
  - Injected dependency interface `AgentStepResultStoreLike`:
    - `issue(namespaceId: string, storageId: string, identity: AgentStepResultCapabilityIdentity): Promise<AgentStepResultIssueResult>`
    - `submit(token: string, business: unknown, observed?: Partial<AgentStepResultObservedIdentity>): Promise<AgentStepResultSubmitResult>`
    - `getByAttempt(namespaceId: string, storageId: string, attemptId: string): Promise<AgentStepResultSubmitted | null>`
    - `list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]>`
  - Class `FilesystemAgentStepResultRepository` implementing `AgentStepResultRepository` by delegating to `store: AgentStepResultStoreLike`.
  - Helper factory `createFilesystemAgentStepResultRepository(store: AgentStepResultStoreLike)`.
- **Facade Helper in `factory/lib/agent-step-result-store.mjs`**:
  - Export `createAgentStepResultRepository(dataRoot, options)`:
    Instantiates `new AgentStepResultStore(dataRoot, options)` and returns `createFilesystemAgentStepResultRepository(store)`.
  - Re-export `FilesystemAgentStepResultRepository` and `createFilesystemAgentStepResultRepository` from `../runtime/factory-operational.mjs`.

### 3. OracleExecution / OracleDefinition
- **Port Interface**: `factory/src/ports/persistence/oracle-execution-repository.ts`
  - Import types `OracleDefinition` from `../../domain/oracle/oracle-definition.js` (and baseline types if applicable).
  - Interface `OracleExecutionRepository`:
    - `list(): Promise<OracleDefinition[]>`
    - `get(id: string): Promise<OracleDefinition | null>`
- **Adapter**: `factory/src/adapters/persistence/filesystem-oracle-execution-repository.ts`
  - Injected dependency interface `OracleDefinitionRegistryLike`:
    - `initialize(): Promise<unknown>`
    - `get(id: string): OracleDefinition | null`
    - `items`: `Map<string, OracleDefinition>` or a `list(): OracleDefinition[]` getter/method.
  - Class `FilesystemOracleExecutionRepository` implementing `OracleExecutionRepository`.
    - Methods `list()` (returning values of `registry.items` or `registry.list()`) and `get(id)` (calling `registry.get(id)`).
  - Helper factory `createFilesystemOracleExecutionRepository(registry: OracleDefinitionRegistryLike)`.
- **Facade Helper in `factory/lib/oracle-definition.mjs`**:
  - Export `createOracleDefinitionRepository(root)`:
    Instantiates `new OracleDefinitionRegistry(root)`, awaits `.initialize()`, and returns `createFilesystemOracleExecutionRepository(registry)`.
  - Re-export `FilesystemOracleExecutionRepository` and `createFilesystemOracleExecutionRepository` from `../runtime/factory-operational.mjs`.

### 4. Barrels & Operational Entrypoint
- **`factory/src/ports/persistence/index.ts`**:
  - Re-export ports and related types for `agent-step-attempt-repository.ts`, `agent-step-result-repository.ts`, and `oracle-execution-repository.ts`.
- **`factory/src/adapters/persistence/index.ts`**:
  - Re-export classes and factory functions:
    - `FilesystemAgentStepAttemptRepository`, `createFilesystemAgentStepAttemptRepository`
    - `FilesystemAgentStepResultRepository`, `createFilesystemAgentStepResultRepository`
    - `FilesystemOracleExecutionRepository`, `createFilesystemOracleExecutionRepository`
- **`factory/src/entrypoints/factory-operational.ts`**:
  - Ensure all exports from `ports/persistence` and `adapters/persistence` are included (already covered via `export * from '../ports/persistence/index.js'` and `export * from '../adapters/persistence/index.js'`).

### 5. Tests
- **`factory/tests/test-repository-ports-adapters.mjs`**:
  - Extend with scenario tests for:
    1. `AgentStepAttemptRepository` / `FilesystemAgentStepAttemptRepository`:
       - Append starting attempt, list attempts, verify transitions and identity checks.
       - Use facade `createAgentStepAttemptRepository(root)`.
    2. `AgentStepResultRepository` / `FilesystemAgentStepResultRepository`:
       - Issue capability token, submit structured result, retrieve by attempt (`getByAttempt`), list ledger events.
       - Use facade `createAgentStepResultRepository(root)`.
    3. `OracleExecutionRepository` / `FilesystemOracleExecutionRepository`:
       - Create test oracle definition file in a temp directory.
       - Call `createOracleDefinitionRepository(definitionsDir)`.
       - Test `list()` and `get(id)`.

---

## Verification Plan

### Automated Test Commands
1. Run updated repository ports/adapters test:
   `node factory/tests/test-repository-ports-adapters.mjs`
2. Run other existing storage and oracle tests to guarantee no regressions:
   `node factory/tests/test-storage-kernel.mjs`
   `node factory/tests/test-agent-step-attempt-store-source.mjs`
   `node factory/tests/test-agent-step-result-store-source.mjs`
   `node factory/tests/test-oracle-definition-registry.mjs`

---

## Key Files to Create / Touch
- `factory/src/ports/persistence/agent-step-attempt-repository.ts` (CREATE)
- `factory/src/ports/persistence/agent-step-result-repository.ts` (CREATE)
- `factory/src/ports/persistence/oracle-execution-repository.ts` (CREATE)
- `factory/src/ports/persistence/index.ts` (MODIFY)
- `factory/src/adapters/persistence/filesystem-agent-step-attempt-repository.ts` (CREATE)
- `factory/src/adapters/persistence/filesystem-agent-step-result-repository.ts` (CREATE)
- `factory/src/adapters/persistence/filesystem-oracle-execution-repository.ts` (CREATE)
- `factory/src/adapters/persistence/index.ts` (MODIFY)
- `factory/lib/agent-step-attempt-store.mjs` (MODIFY)
- `factory/lib/agent-step-result-store.mjs` (MODIFY)
- `factory/lib/oracle-definition.mjs` (MODIFY)
- `factory/tests/test-repository-ports-adapters.mjs` (MODIFY)
