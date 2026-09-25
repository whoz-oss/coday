# Migration Phase 2 : Isolation des types et événements AgentOS derrière AgentRuntimeGateway

## Context & Objectives

This plan addresses Phase 2 of the TypeScript migration for the Factory runtime: isolating all AgentOS interactions, types, DTOs, and event flows behind a strict domain port (`AgentRuntimeGateway`) in `factory/src/ports/agent-runtime-gateway.ts` and adapters under `factory/src/adapters/agentos/`.

In accordance with `ADR_TYPESCRIPT_MIGRATION.md`, `DEPENDENCY_MATRIX.md`, and `AUTHORITY_SOURCES.md`:
1. The TypeScript sources in `factory/src/` become the canonical implementation and single source of truth for AgentOS gateway and adapter logic.
2. The isolated toolchain (`factory/toolchain`) compiles these sources into the operational single-file ESM bundle `factory/runtime/factory-operational.mjs`.
3. `factory/lib/agentos.mjs` is transformed into a stateless compatibility facade that delegates 100% of its runtime calls to `factory/runtime/factory-operational.mjs` with zero dual-write and zero duplicate state.
4. Legacy callers of `agentos.mjs` (such as `review-agentos-adapter.mjs`, `forge-story-analysis.mjs`, `adversarial-review.mjs`, etc.) continue working without signature or behavior regressions.
5. Absolute runtime autonomy is maintained (standalone Node >=22.12.0 execution without product `node_modules`, `pnpm`, `nx`, or Gradle).

---

## Architectural & Modular Structure

### 1. Ports & Vocabulary
Location: `factory/src/ports/agent-runtime-gateway.ts` (and optional helper files in `factory/src/ports/` if needed).

**Domain Vocabulary Types:**
- `RuntimeExecutionId`: Branded type `string & { readonly __brand: 'RuntimeExecutionId' }` or aliased type representing a case ID or runtime execution handle.
- `WorkerIdentity`: `{ namespaceId: string; workerName: string }`
- `WorkerInspectionResult`: `{ ok: boolean; reason: string | null; worker: WorkerConfig | null; rootPath: string | null; integration: IntegrationConfig | null }`
- `RuntimeEvent`: Discriminated union of domain events normalized from AgentOS events (`RuntimeStatusEvent`, `RuntimeMessageEvent`, `RuntimeQuestionEvent`, `RuntimeAnswerEvent`, `RuntimeWorkerSelectedEvent`, `RuntimeWorkerFinishedEvent`, `RuntimeToolResponseEvent`, `RuntimeAgentRunningEvent`).
- `HumanInputRequest`: `{ questionId: string; question: string; timestamp?: string }`
- `RuntimeFailure`: `{ code: string; message: string; details?: Record<string, unknown> }`
- `StructuredResultRef`: `{ resultId?: string; resultHash?: string; binding?: Record<string, unknown> }`
- `ExecutionOptions`: `{ namespaceId: string; workerName: string; brief: string; caseId?: string; startTimeoutMs?: number; workTimeoutMs?: number }`
- `ObserveOptions`: `{ startTimeoutMs?: number; workTimeoutMs?: number; sliceAfterEventId?: string }`
- `ExecutionObservation`: `{ status: 'finished' | 'pending_question' | 'case_busy' | 'start_timeout' | 'work_timeout' | 'killed' | 'case_error' | 'error'; caseStatus: string | null; message: string; events: RuntimeEvent[]; agentsSelected: string[]; agentTurns: number; toolCallCount: number; failedToolCalls: Record<string, number>; killedByBudget: boolean; anchored: boolean; llmModels: Array<{ agentName: string | null; llmProvider: string | null; llmModel: string | null }> }`
- `ResultBinding`: `{ secret: string; binding: Record<string, unknown> }`

**Port Interface `AgentRuntimeGateway`:**
```typescript
export interface AgentRuntimeGateway {
  inspectWorker(namespaceId: string, workerName: string, options?: { mode?: 'general' | 'writable' | 'readonly'; repoRoot?: string }): Promise<WorkerInspectionResult>
  startExecution(options: ExecutionOptions): Promise<RuntimeExecutionId>
  observeExecution(executionId: RuntimeExecutionId, observerOptions?: ObserveOptions): Promise<ExecutionObservation>
  bindResultChannel(executionId: RuntimeExecutionId, binding: ResultBinding): Promise<void>
  answerQuestion(executionId: RuntimeExecutionId, questionId: string, answer: string): Promise<void>
  interruptExecution(executionId: RuntimeExecutionId): Promise<void>
  terminateExecution(executionId: RuntimeExecutionId): Promise<void>
}
```

---

### 2. Adapters (`factory/src/adapters/agentos/`)

The implementation is partitioned into modular TypeScript files inside `factory/src/adapters/agentos/`:

#### A. `agentos-dtos.ts`
Private REST DTOs for AgentOS backend interfaces. Kept strictly private/internal to this adapter module.
- `CaseDTO`: `{ id: string; namespaceId: string; title: string; ... }`
- `CaseEventDTO`: Base interface with `id: string`, `type: string`, `timestamp: string`.
- Specialized Event DTOs: `CaseStatusEventDTO` (status: `RUNNING` | `IDLE` | `KILLED` | `ERROR`), `MessageEventDTO`, `QuestionEventDTO`, `AnswerEventDTO`, `AgentSelectedEventDTO`, `AgentFinishedEventDTO`, `AgentRunningEventDTO`, `ToolResponseEventDTO`.
- `AgentConfigDTO`: `{ name: string; enabled?: boolean; subAgents?: string[]; integrations?: Record<string, unknown> }`
- `IntegrationConfigDTO`: `{ name: string; integrationType: string; parameters?: { rootPath?: string; readOnly?: boolean } }`

#### B. `agentos-http-client.ts`
Pure HTTP transport using native `fetch` with configurable timeout, `X-External-User-Id` (from `process.env.FACTORY_USER`), base URL (`process.env.AGENTOS_URL`), and explicit error throwing.
Endpoints wrapped:
- `POST /api/cases`
- `POST /api/cases/:id/messages`
- `GET /api/cases/:id`
- `GET /api/case-events/by-parentId/:id`
- `POST /api/cases/:id/kill`
- `GET /api/agent-configs/by-parentId/:namespaceId`
- `GET /api/integration-configs?namespaceId=:namespaceId`
- `PUT /internal/factory/cases/:id/step-result-binding`

#### C. `agentos-event-translator.ts`
Pure functions for:
- Translating `CaseEventDTO[]` into domain `RuntimeEvent[]`.
- `sliceAfterId(events, baselineId)` handling Cypher chronological order and non-anchored fallbacks without timestamp string comparisons.
- Extracting last agent message content.
- Collecting selected worker names (`AgentSelectedEvent`).
- Counting tool calls and computing failed tool call counts (`ToolResponseEvent` with `success === false`).
- Collecting LLM models (`AgentRunningEvent` & `AgentFinishedEvent`).
- Unanswered question detection (`QuestionEvent` vs `AnswerEvent.questionId`).

#### D. `agentos-runtime-observer.ts`
Implements polling and quiescence observation logic:
- Manages dual deadlines (`startTimeoutMs` and `workTimeoutMs`).
- F7 multi-turn quiescence tracking: continuously advances `runningIndex` to the most recent `RUNNING` status event before searching for quiescent status (`IDLE`, `KILLED`, `ERROR`).
- Idempotent auto-kill on timeouts via client.

#### E. `agentos-capability-inspector.ts`
Encapsulates preflight inspections:
- Worker existence & enabled check.
- `subAgents` check (must be empty/absent for phase role).
- Integration workspace checks for General, Writable (`preflightWritableWorkspace`), and Read-Only (`preflightReadOnlyWorkspace`).
- Canonical path comparisons using native node filesystem realpaths / root normalization.

#### F. `agentos-runtime-adapter.ts`
Concrete implementation of `AgentRuntimeGateway`:
- Composes client, translator, observer, inspector.
- Automatically registers active execution IDs via `registerActiveCase` / `unregisterActiveCase` (or legacy active case management in `factory/src/lib/active-case.ts`).
- Offers standalone instance creation function: `createAgentOsRuntimeAdapter(config?: AgentOsAdapterConfig): AgentRuntimeGateway`.

---

### 3. Entrypoint & Toolchain Bundle (`factory/src/entrypoints/factory-operational.ts`)

1. Export domain types, gateway interface, DTO adapters, factory functions, and high-level execution helper functions from `factory/src/entrypoints/factory-operational.ts`:
   - `createAgentOsRuntimeAdapter`
   - `createAgentOsHttpClient`
   - Helper functions providing identical contract outputs for legacy callers (`createCase`, `postMessage`, `bindFactoryStepResult`, `getCase`, `listEvents`, `killCase`, `listAgents`, `preflightAgent`, `listIntegrations`, `preflightWorkspace`, `preflightWritableWorkspace`, `preflightReadOnlyWorkspace`, `runAgentTurn`).
2. Run build toolchain (`npm --prefix factory/toolchain run build`) to bundle everything into `factory/runtime/factory-operational.mjs`.

---

### 4. Compatibility Facade (`factory/lib/agentos.mjs`)

Refactor `factory/lib/agentos.mjs` to be a pure, stateless re-export/delegation facade:
```javascript
import {
  createCase as operationalCreateCase,
  postMessage as operationalPostMessage,
  bindFactoryStepResult as operationalBindFactoryStepResult,
  getCase as operationalGetCase,
  listEvents as operationalListEvents,
  killCase as operationalKillCase,
  listAgents as operationalListAgents,
  preflightAgent as operationalPreflightAgent,
  listIntegrations as operationalListIntegrations,
  preflightWorkspace as operationalPreflightWorkspace,
  preflightWritableWorkspace as operationalPreflightWritableWorkspace,
  preflightReadOnlyWorkspace as operationalPreflightReadOnlyWorkspace,
  runAgentTurn as operationalRunAgentTurn,
} from '../runtime/factory-operational.mjs'

export const createCase = operationalCreateCase
export const postMessage = operationalPostMessage
export const bindFactoryStepResult = operationalBindFactoryStepResult
export const getCase = operationalGetCase
export const listEvents = operationalListEvents
export const killCase = operationalKillCase
export const listAgents = operationalListAgents
export const preflightAgent = operationalPreflightAgent
export const listIntegrations = operationalListIntegrations
export const preflightWorkspace = operationalPreflightWorkspace
export const preflightWritableWorkspace = operationalPreflightWritableWorkspace
export const preflightReadOnlyWorkspace = operationalPreflightReadOnlyWorkspace
export const runAgentTurn = operationalRunAgentTurn
```
*Note:* No state, no duplicate HTTP logic, no dual-write in `factory/lib/agentos.mjs`.

---

### 5. Contract Tests & Verification Strategy

1. **Adapter & Gateway Unit/Contract Tests:**
   Create `factory/tests/test-agentos-runtime-adapter.mjs` testing:
   - Event translation (F7 multi-turn, questions, LLM model collection, tool call failures).
   - Slice after ID logic with Cypher ordering.
   - Preflight checks (worker missing, subAgents present, workspace colocalization).
   - Gateway methods (`inspectWorker`, `startExecution`, `observeExecution`, `bindResultChannel`, `terminateExecution`).
   - Facade identity & delegation.

2. **Toolchain Checks:**
   - Strict TypeScript check: `npm --prefix factory/toolchain run check` (`tsc --noEmit`).
   - Bundle build: `npm --prefix factory/toolchain run build`.
   - Verification of generated bundle & contracts: `npm --prefix factory/toolchain run verify:generated` (runs `node factory/tests/typescript-factory-operational.mjs`).

3. **Regression Tests:**
   - Execute existing test suite for Factory and operational contracts:
     - `node factory/tests/test-f7.mjs`
     - `node factory/tests/test-review-adapter.mjs`
     - `node factory/tests/test-shutdown.mjs`
     - `node factory/tests/test-factory-binding-source.mjs`
     - `node factory/tests/test-agent-step-attempt-store-source.mjs`
     - `node factory/tests/test-factory-agent-step-executor-source.mjs`
   - Run NX/pnpm affected test check as specified in the environment prompt:
     `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`

---

## Deliverables & Step-by-Step Implementation Plan

### Step 1: Define Port Interface & Supporting Types
- **Create File:** `factory/src/ports/agent-runtime-gateway.ts`
  - Define `RuntimeExecutionId`, `WorkerIdentity`, `WorkerConfig`, `IntegrationConfig`, `WorkerInspectionResult`, `RuntimeEvent` (discriminated union), `HumanInputRequest`, `ExecutionOptions`, `ObserveOptions`, `ExecutionObservation`, `ResultBinding`.
  - Define `AgentRuntimeGateway` interface.

### Step 2: Implement AgentOS Adapter Subsystem
- **Create File:** `factory/src/adapters/agentos/agentos-dtos.ts`
  - Internal interfaces for AgentOS REST DTOs (`CaseDTO`, `CaseEventDTO`, `AgentConfigDTO`, `IntegrationConfigDTO`).
- **Create File:** `factory/src/adapters/agentos/agentos-http-client.ts`
  - Lightweight HTTP fetch client wrapping AgentOS REST endpoints with timeouts, headers, and error formatting.
- **Create File:** `factory/src/adapters/agentos/agentos-event-translator.ts`
  - Pure functions for event mapping, `sliceAfterId`, message extraction, question/answer pairing, LLM model collection, and tool call metrics.
- **Create File:** `factory/src/adapters/agentos/agentos-capability-inspector.ts`
  - Preflight logic (`preflightAgent`, `preflightWorkspace`, `preflightWritableWorkspace`, `preflightReadOnlyWorkspace`).
- **Create File:** `factory/src/adapters/agentos/agentos-runtime-observer.ts`
  - Polling loop, start timeout, work timeout, F7 multi-turn quiescence tracking.
- **Create File:** `factory/src/adapters/agentos/agentos-runtime-adapter.ts`
  - Implementation of `AgentRuntimeGateway` composing client, translator, observer, and capability inspector. Integrates active case tracking (`registerActiveCase`/`unregisterActiveCase`).
- **Create File:** `factory/src/adapters/agentos/index.ts`
  - Barrel exports for adapter components.

### Step 3: Wire into Entrypoint & Re-generate Operational Bundle
- **Modify File:** `factory/src/entrypoints/factory-operational.ts`
  - Export ports, adapter types, `createAgentOsRuntimeAdapter`, and legacy compatibility function bridges that map parameter signatures.
- **Run Toolchain Build:**
  - Execute `npm --prefix factory/toolchain run check`
  - Execute `npm --prefix factory/toolchain run build`
  - Produce updated `factory/runtime/factory-operational.mjs`.

### Step 4: Refactor `factory/lib/agentos.mjs` Compatibility Facade
- **Modify File:** `factory/lib/agentos.mjs`
  - Replace implementation body with direct re-exports / delegations from `../runtime/factory-operational.mjs`.
  - Ensure zero duplicated state or HTTP logic in `lib/agentos.mjs`.

### Step 5: Write Tests & Run Verification
- **Create File:** `factory/tests/test-agentos-runtime-adapter.mjs`
  - Add comprehensive contract & unit tests covering adapter translation, gateway methods, and facade re-exports.
- **Execute Verification Suite:**
  1. `npm --prefix factory/toolchain run check`
  2. `npm --prefix factory/toolchain run build`
  3. `npm --prefix factory/toolchain run verify:generated`
  4. Run operational test scripts: `node factory/tests/test-f7.mjs`, `node factory/tests/test-review-adapter.mjs`, `node factory/tests/test-shutdown.mjs`, `node factory/tests/test-factory-binding-source.mjs`, `node factory/tests/test-agentos-runtime-adapter.mjs`.
  5. Run repository project tests: `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`.

---

## Verification & Acceptance Criteria Matrix

| Criterion | Target | Verification Method |
|---|---|---|
| Strict TypeScript Compilation | Zero errors under strict mode | `npm --prefix factory/toolchain run check` |
| Operational Bundle Generation | Updated `factory-operational.mjs` | `npm --prefix factory/toolchain run build` |
| Operational Bundle Contract | Bundle self-contained, tests pass | `node factory/tests/typescript-factory-operational.mjs` |
| Zero Dual-Write / Duplicate State | `agentos.mjs` purely delegates | File review & `test-review-adapter.mjs` |
| Event Isolation | DTOs/endpoints restricted to `src/adapters/agentos/` | Grep analysis across `factory/src/` |
| F7 Quiescence & Timeout Rules | Identical F7 multi-turn behavior | `node factory/tests/test-f7.mjs` & adapter tests |
| Test Suite Regression | All existing tests pass | Execution of test scripts & Nx affected test command |
