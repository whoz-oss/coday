# Plan : Migration Tranche 5 - Agent Attempts

## 1. Context & Objectives
Migrate Tranche 5 (agent attempts & step result stores and step execution logic) of the Factory core into structured TypeScript under `factory/src/`:
- `factory/lib/agent-step-attempt-store.mjs` -> `factory/src/domain/agent-attempt/agent-step-attempt-store.ts` (and interfaces/types)
- `factory/lib/agent-step-result-store.mjs` -> `factory/src/domain/agent-attempt/agent-step-result-store.ts` (and interfaces/types)
- `factory/lib/factory-agent-step-executor.mjs` -> `factory/src/application/agent-attempt/factory-agent-step-executor.ts` (or domain/application split as appropriate)

Key constraints and requirements:
1. Pure domain rules & validation, invariants, attempt status/result types in `factory/src/domain/agent-attempt/`.
2. Storage and step execution orchestration logic in `factory/src/domain/agent-attempt/` (or `factory/src/application/agent-attempt/` for orchestration).
3. Strictly follow the Dependency Matrix (`DEPENDENCY_MATRIX.md`): no direct imports of `fs`, Git CLI, or AgentOS HTTP inside pure domain modules if applicable; storage kernel / Node `fs` primitives used via storage kernel or isolated helpers where appropriate.
4. Export all public types, classes, functions via `factory/src/entrypoints/factory-operational.ts`.
5. Re-generate the operational bundle `factory/runtime/factory-operational.mjs` using `node factory/toolchain/build.mjs`.
6. Update legacy facades in `factory/lib/`:
   - `factory/lib/agent-step-attempt-store.mjs`
   - `factory/lib/agent-step-result-store.mjs`
   - `factory/lib/factory-agent-step-executor.mjs`
   They must re-export from `../runtime/factory-operational.mjs` without duplicating state.
7. Preserve 100% observable behavior and API backward compatibility.
8. Add bundle assertions to `factory/tests/typescript-factory-operational.mjs`.
9. Verify all relevant tests pass.

---

## 2. Analysis of Target Source Files

### 2.1 `factory/lib/agent-step-attempt-store.mjs`
- Exported Constants:
  - `AGENT_STEP_ATTEMPT_STATUSES = ['starting', 'running', 'succeeded', 'failed', 'indeterminate', 'interrupted']`
- Exported Class: `AgentStepAttemptStore`
  - Constructor: `constructor(dataRoot)`
  - Methods:
    - `path(namespaceId, storageId)` -> string path (`join(dataRoot, 'workflows', namespaceId, storageId, 'agent-step-attempts.jsonl')`)
    - `async list(namespaceId, storageId)` -> Array of attempts parsed from JSONL
    - `async append(namespaceId, storageId, attempt)` -> validates attempt, checks transitions, appends to JSONL file using durable sync helper (`appendDurable`), returns `attempt`.
- Validation and Invariants:
  - `SAFE_ID` regex (`/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/`)
  - Status transition state machine:
    - `starting` -> `['running', 'failed', 'interrupted']`
    - `running` -> `['succeeded', 'failed', 'indeterminate', 'interrupted']`
  - Validation checks timestamps (`startedAt`, `finishedAt`), briefHash (`/^sha256:[0-9a-f]{64}$/`), workflowRevisionAtStart >= 1, attemptNumber >= 1, caseId rules per status, etc.
  - Concurrency lock per `namespaceId\0storageId`.

### 2.2 `factory/lib/agent-step-result-store.mjs`
- Exported Functions:
  - `hashAgentStepResult(value)` -> sha256 of canonical JSON
- Exported Class: `AgentStepResultStore`
  - Constructor: `constructor(dataRoot, { clock = () => new Date(), ttlMs = 15 * 60 * 1000 } = {})`
  - Methods:
    - `path(namespaceId, storageId)` -> string path
    - `async list(namespaceId, storageId)`
    - `indexLedger(namespaceId, storageId, events)`
    - `async initialize()` -> scans workflows directory, builds capability and attempt indexes
    - `async locked(key, work)`
    - `async issue(namespaceId, storageId, identity)` -> issues a submission token, persists `capability-issued` event
    - `async resolve(token)` -> looks up token hash in capability index
    - `async submit(token, business, observed = {})` -> validates schema, checks expiration/identity, persists `result-submitted` event
    - `async getByAttempt(namespaceId, storageId, attemptId)` -> returns submitted result for attempt
- Types and Invariants:
  - Business result schema validation (`validBusiness`): status (`PASS` | `FAIL`), summary, claims (`modifiedFiles`), artifacts, findings.
  - Canonical JSON stringification and sha256 hash generation.
  - Safe timing-safe comparison (`safeEqual`).

### 2.3 `factory/lib/factory-agent-step-executor.mjs`
- Exported Functions:
  - `artifactEvidenceIdempotencyKey(attemptId, artifactPath)`
  - `parseAgentStepResult(message)`
  - `materializeInlineArtifact({ result, repoRoot, workflowId, stepId, attemptId, expectedKind, maxBytes })`
  - `executeAgentStepAttempt(input)`
  - `hashAgentBrief(brief)`
  - `hashStructuredAgentResult(result)`
- Dependencies & Dynamic Imports:
  - Imports `bindFactoryStepResult`, `createCase`, `preflightAgent`, `preflightReadOnlyWorkspace`, `preflightWritableWorkspace`, `runAgentTurn` from `./agentos.mjs` (can be passed via `agentOps` or default to the AgentOS facade).
  - Imports `validateWorkflowEvidenceInput` from `./workflow-evidence.mjs`.
  - Imports `validateWorkflowTransitionRequest` from `./workflow-transition-policy.mjs`.
- Invariants:
  - Path traversal checks (`inside`, `safeSegment`), atomic file write for inline artifacts (`.tmp` write then `rename`), evidence recording, transition validation, etc.

---

## 3. Architecture & File Structure for Tranche 5

We will create the TS domain and application modules in `factory/src/`:

### 3.1 Domain Layer: `factory/src/domain/agent-attempt/`
- `factory/src/domain/agent-attempt/agent-step-attempt.ts`
  - Domain interfaces & types:
    - `AgentStepAttemptStatus` ('starting' | 'running' | 'succeeded' | 'failed' | 'indeterminate' | 'interrupted')
    - `AgentStepAttempt` interface
    - Constants: `AGENT_STEP_ATTEMPT_STATUSES`
    - Domain validation functions: `validateAgentStepAttempt(attempt)`, transition validator.
- `factory/src/domain/agent-attempt/agent-step-result.ts`
  - Domain interfaces & types:
    - `AgentStepResultBusiness`, `AgentStepResultClaim`, `AgentStepResultArtifact`, `AgentStepResultFinding`
    - Capability issue identity, capability record, result submitted event
    - Business result schema validation (`validateBusinessResult`), canonical JSON helper, hashing functions (`hashAgentStepResult`, `hashAgentBrief`, `hashStructuredAgentResult`).
- `factory/src/domain/agent-attempt/agent-step-attempt-store.ts`
  - `AgentStepAttemptStore` class implementing filesystem persistence & locking for step attempts using storage kernel / `appendDurable` primitives.
- `factory/src/domain/agent-attempt/agent-step-result-store.ts`
  - `AgentStepResultStore` class implementing index maintenance, token issuing, capability resolution, submission, and persistence for step results.

### 3.2 Application Layer: `factory/src/application/agent-attempt/`
- `factory/src/application/agent-attempt/factory-agent-step-executor.ts`
  - Helper functions: `artifactEvidenceIdempotencyKey`, `parseAgentStepResult`, `materializeInlineArtifact`, `executeAgentStepAttempt`.
  - Interfaces for input parameters & dependencies (`ExecuteAgentStepAttemptInput`, `AgentOps`, etc.).

---

## 4. Step-by-Step Action Plan

### Step 1: Create Domain Modules in `factory/src/domain/agent-attempt/`
1. `factory/src/domain/agent-attempt/agent-step-attempt.ts`:
   - Export `AGENT_STEP_ATTEMPT_STATUSES` array and type.
   - Interface `AgentStepAttempt`.
   - Function `validateAgentStepAttempt`.
2. `factory/src/domain/agent-attempt/agent-step-result.ts`:
   - Export types and interfaces for step results, findings, artifacts, claims, capabilities.
   - Validation helper `validBusinessResult`.
   - Hashing helpers: `hashAgentStepResult`, `hashAgentBrief`, `hashStructuredAgentResult`, `canonicalJson`.
3. `factory/src/domain/agent-attempt/agent-step-attempt-store.ts`:
   - `AgentStepAttemptStore` class preserving exact logic, file paths, and locks.
4. `factory/src/domain/agent-attempt/agent-step-result-store.ts`:
   - `AgentStepResultStore` class preserving index initialization, lock mechanism, token generation (using `node:crypto`), timing-safe comparison, and result validation.

### Step 2: Create Application Module in `factory/src/application/agent-attempt/`
1. `factory/src/application/agent-attempt/factory-agent-step-executor.ts`:
   - Port all functions from `factory/lib/factory-agent-step-executor.mjs`.
   - Import domain validation / hashing / types from domain modules.
   - Import evidence and transition policy helpers from `../evidence/workflow-evidence.js` and `../workflow/workflow-transition-policy.js`.
   - Default `agentOps` to operational entrypoint exports / AgentOS operations if not provided.

### Step 3: Update Operational Entrypoint
Update `factory/src/entrypoints/factory-operational.ts`:
- Re-export all domain agent-attempt classes, types, constants, and functions:
  `export * from '../domain/agent-attempt/agent-step-attempt.js'`
  `export * from '../domain/agent-attempt/agent-step-result.js'`
  `export * from '../domain/agent-attempt/agent-step-attempt-store.js'`
  `export * from '../domain/agent-attempt/agent-step-result-store.js'`
- Re-export application agent-step-executor functions:
  `export * from '../application/agent-attempt/factory-agent-step-executor.js'`

### Step 4: Rebuild Operational Bundle
Run `node factory/toolchain/build.mjs` to re-generate `factory/runtime/factory-operational.mjs` and update `factory/dist/factory-operational/factory-operational.meta.json`.

### Step 5: Replace Legacy `.mjs` Files with Facades
1. `factory/lib/agent-step-attempt-store.mjs`:
   - Re-export `AGENT_STEP_ATTEMPT_STATUSES` and `AgentStepAttemptStore` from `../runtime/factory-operational.mjs`.
2. `factory/lib/agent-step-result-store.mjs`:
   - Re-export `AgentStepResultStore` and `hashAgentStepResult` from `../runtime/factory-operational.mjs`.
3. `factory/lib/factory-agent-step-executor.mjs`:
   - Re-export `artifactEvidenceIdempotencyKey`, `parseAgentStepResult`, `materializeInlineArtifact`, `executeAgentStepAttempt`, `hashAgentBrief`, `hashStructuredAgentResult` from `../runtime/factory-operational.mjs`.

### Step 6: Update Bundle Tests
In `factory/tests/typescript-factory-operational.mjs`:
- Add assertions verifying that attempt/result store and executor modules/inputs are present in `metafile.inputs`.
- Add contract tests for `AgentStepAttemptStore`, `AgentStepResultStore`, and `executeAgentStepAttempt` against the bundle exports.

### Step 7: Verification & Testing
Execute test commands:
- `node factory/tests/test-agent-step-attempt-store-source.mjs`
- `node factory/tests/test-agent-step-result-store-source.mjs`
- `node factory/tests/test-factory-agent-step-executor-source.mjs`
- `node factory/tests/test-agent-step-result-route-source.mjs`
- `node factory/tests/test-factory-inline-artifact-source.mjs`
- `node factory/tests/test-factory-binding-source.mjs`
- `node factory/tests/typescript-factory-operational.mjs`
- `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`

---

## 5. Risk Mitigation & Invariants Check
- **Backward Compatibility**: Standard export signatures and return shapes from `.mjs` facades remain identical.
- **No Duplicate State**: Classes and functions are instantiated/executed from the bundle.
- **Dependency Matrix**: Pure domain files in `src/domain/agent-attempt/` contain no external unapproved imports; storage operations use node native or storage kernel primitives.
