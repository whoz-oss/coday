# Migration Phase 2 Implementation Plan: Isolation des types et événements AgentOS derrière le port AgentRuntimeGateway

## Overview
Phase 2 isolates AgentOS types, DTOs, HTTP calls, and polling/event logic behind a clean domain/port interface `AgentRuntimeGateway` (`factory/src/ports/agent-runtime-gateway.ts`) and adapters in `factory/src/adapters/agentos/`.
The legacy module `factory/lib/agentos.mjs` is refactored into a compatibility facade that delegates all operations to the generated operational bundle / TypeScript implementation (`factory/runtime/factory-operational.mjs`), eliminating dual-write, duplicate state, and leak of AgentOS DTOs or endpoints outside `factory/src/adapters/agentos/`.

---

## Targeted Files & Modules

### 1. New TypeScript Domain / Ports (`factory/src/ports/`)
- `factory/src/ports/agent-runtime-gateway.ts`: Domain vocabularies, execution options/results, and port interface `AgentRuntimeGateway`.
- `factory/src/ports/runtime-types.ts` (or exported from `agent-runtime-gateway.ts`):
  - `RuntimeExecutionId` (branded string or string type alias)
  - `WorkerIdentity` (`{ namespaceId: string; workerName: string }` or similar)
  - `RuntimeEvent` (discriminated union for domain events: status, message, question, answer, agent selection, tool response, LLM model info)
  - `HumanInputRequest` (`{ questionId: string; question: string }`)
  - `RuntimeFailure` (`{ status: string; caseStatus: string | null; message: string; ... }`)
  - `StructuredResultRef` / `ResultBinding` (`{ attemptId: string; token: string; ... }`)
  - `WorkerInspectionResult` (`{ ok: boolean; reason: string | null; agent: Record<string, unknown> | null }`)
  - `ExecutionOptions` (`{ namespaceId: string; caseId?: string; title?: string; agentName: string; brief: string; startTimeoutMs?: number; workTimeoutMs?: number }`)
  - `ObserveOptions` (`{ baselineId?: string | null; startTimeoutMs?: number; workTimeoutMs?: number }`)
  - `ExecutionObservation` / `TurnResult` (matching legacy `runAgentTurn` return shape to ensure zero regressions)

### 2. New AgentOS Adapter Module (`factory/src/adapters/agentos/`)
- `factory/src/adapters/agentos/agentos-dtos.ts`: Private DTO interfaces for AgentOS REST responses (CaseDTO, CaseEvent DTOs like `CaseStatusEvent`, `QuestionEvent`, `AnswerEvent`, `AgentSelectedEvent`, `AgentFinishedEvent`, `ToolResponseEvent`, `AgentConfig`, `IntegrationConfig`).
- `factory/src/adapters/agentos/agentos-http-client.ts`: Pure HTTP transport over REST endpoints (`/api/cases`, `/api/case-events`, `/api/agent-configs`, `/api/integration-configs`, `/internal/factory/...`) using native `fetch` with timeouts and explicit error handling. Respects `process.env.AGENTOS_URL`, `process.env.FACTORY_USER`, and `process.env.FACTORY_AGENTOS_BINDING_SECRET`.
- `factory/src/adapters/agentos/agentos-event-translator.ts`: Translates raw AgentOS event DTOs into Factory domain `RuntimeEvent` types, filtering/mapping event chains, handling slicing by ID (`sliceAfterId`).
- `factory/src/adapters/agentos/agentos-runtime-observer.ts`: Polling & quiescence observer (RUNNING phase, IDLE/KILLED/ERROR detection, F7 multi-turn handling, unanswered questions detection).
- `factory/src/adapters/agentos/agentos-capability-inspector.ts`: Preflight checks (`inspectWorker` / `preflightAgent`, `preflightWorkspace`, `preflightWritableWorkspace`, `preflightReadOnlyWorkspace`).
- `factory/src/adapters/agentos/agentos-runtime-adapter.ts`: Implements `AgentRuntimeGateway` composing client, translator, observer, and inspector. Exposes helper factory `createAgentOsRuntimeAdapter(options?: { baseUrl?: string; user?: string; bindingSecret?: string })`.

### 3. Entrypoint & Bundle Export (`factory/src/entrypoints/factory-operational.ts`)
- Re-export `AgentRuntimeGateway`, supporting types, `createAgentOsRuntimeAdapter`, and legacy-compatible wrapper functions/instances from `factory/src/entrypoints/factory-operational.ts`.
- Run build toolchain (`npm --prefix factory/toolchain run build`) to update `factory/runtime/factory-operational.mjs`.

### 4. Compatibility Facade Refactoring (`factory/lib/agentos.mjs`)
- Refactor `factory/lib/agentos.mjs` to delegate all exported functions directly to `factory/runtime/factory-operational.mjs` or adapter instances initialized from operational bundle.
- Public functions to delegate:
  - `createCase(namespaceId, title)`
  - `postMessage(caseId, content)`
  - `bindFactoryStepResult(caseId, binding)`
  - `getCase(caseId)`
  - `listEvents(caseId)`
  - `killCase(caseId)`
  - `listAgents(namespaceId)`
  - `preflightAgent(namespaceId, agentName)`
  - `listIntegrations(namespaceId)`
  - `preflightWorkspace(namespaceId, agent, repoRoot)`
  - `preflightWritableWorkspace(namespaceId, agent, repoRoot)`
  - `preflightReadOnlyWorkspace(namespaceId, agent, repoRoot)`
  - `runAgentTurn(caseId, agentName, brief, options)`
- Active case tracking (`setActiveCaseId`, `clearActiveCaseId`) remains properly managed inside `runAgentTurn` via `factory-operational.mjs` bundle exports. No dual-write or secondary state.

### 5. Tests & Verification
- `factory/tests/test-agentos-runtime-adapter.mjs`: Unit & contract test suite for the new adapter using simulated REST responses / event fixtures (testing F7 multi-turn, unanswered questions, preflights, timeout handling, and active-case registration).
- `factory/tests/typescript-factory-operational.mjs`: Updated to verify new gateway and adapter exports from the bundle.

---

## Detailed Step-by-Step Execution Plan

### Step 1: Define Port & Vocabularies
Create `factory/src/ports/agent-runtime-gateway.ts` (and helper type files if needed under `factory/src/ports/`):
- Define types:
  ```ts
  export type RuntimeExecutionId = string & { readonly __brand: unique symbol }
  export interface WorkerIdentity { namespaceId: string; workerName: string }
  export type RuntimeEvent = ...
  export interface HumanInputRequest { questionId: string; question: string }
  export interface RuntimeFailure { status: string; caseStatus: string | null; message: string; ... }
  export interface ResultBinding { attemptId: string; token: string; ... }
  export interface WorkerInspectionResult { ok: boolean; reason: string | null; agent: Record<string, unknown> | null }
  export interface ExecutionObservation { ... }
  ```
- Define `AgentRuntimeGateway` interface:
  ```ts
  export interface AgentRuntimeGateway {
    inspectWorker(namespaceId: string, workerName: string): Promise<WorkerInspectionResult>
    startExecution(options: ExecutionOptions): Promise<RuntimeExecutionId>
    observeExecution(executionId: RuntimeExecutionId, observerOptions?: ObserveOptions): Promise<ExecutionObservation>
    bindResultChannel(executionId: RuntimeExecutionId, binding: ResultBinding): Promise<void>
    answerQuestion(executionId: RuntimeExecutionId, questionId: string, answer: string): Promise<void>
    interruptExecution(executionId: RuntimeExecutionId): Promise<void>
    terminateExecution(executionId: RuntimeExecutionId): Promise<void>
  }
  ```

### Step 2: Implement Private Adapter Submodules
In `factory/src/adapters/agentos/`:
1. `agentos-dtos.ts`:
   - Declare interfaces for `CaseDTO`, `CaseEventDTO` (`CaseStatusEvent`, `QuestionEvent`, `AnswerEvent`, `AgentSelectedEvent`, `AgentFinishedEvent`, `AgentRunningEvent`, `ToolResponseEvent`, `MessageEvent`), `AgentConfigDTO`, `IntegrationConfigDTO`.
2. `agentos-http-client.ts`:
   - Pure HTTP client wrapping `fetch` for GET/POST/PUT endpoints. Handles `X-External-User-Id` and `x-factory-agentos-secret`.
3. `agentos-event-translator.ts`:
   - Logic for `sliceAfterId`, mapping AgentOS DTOs to `RuntimeEvent` types, and collecting LLM models / failed tool calls.
4. `agentos-runtime-observer.ts`:
   - Logic for polling, `findStatusEvent`, `findLastStatusEvent` (F7 rule), `findUnansweredQuestions`, start timeout, work timeout, building observation result.
5. `agentos-capability-inspector.ts`:
   - Preflight checks: `preflightAgent` (`inspectWorker`), `preflightWorkspace`, `preflightWritableWorkspace`, `preflightReadOnlyWorkspace`.
6. `agentos-runtime-adapter.ts`:
   - Class `AgentOsRuntimeAdapter` implementing `AgentRuntimeGateway` as well as providing adapter execution functions (`createCase`, `runAgentTurn`, `preflightAgent`, etc.) using active case tracking (`setActiveCaseId`/`clearActiveCaseId`) from `../lib/active-case.js`.

### Step 3: Bundle Integration & Toolchain Build
1. Update `factory/src/entrypoints/factory-operational.ts`:
   - Export all gateway types, port interface, `AgentOsRuntimeAdapter`, and operational delegation functions.
2. Run build:
   - `npm --prefix factory/toolchain run build`
   - Verify generation of `factory/runtime/factory-operational.mjs`.

### Step 4: Refactor Legacy Facade `factory/lib/agentos.mjs`
- Rewrite `factory/lib/agentos.mjs` so that it imports functions/adapter from `../runtime/factory-operational.mjs` and re-exports/delegates directly to them.
- Ensure all function signatures and return structures match exact expectations of callers (`run.mjs`, `forge-story-analysis.mjs`, `forge-story-edit.mjs`, `review-agentos-adapter.mjs`, `factory-agent-step-executor.mjs`).

### Step 5: Verification & Contract Tests
1. Run `npm --prefix factory/toolchain run check` (`tsc --noEmit`).
2. Run `npm --prefix factory/toolchain run build`.
3. Run `npm --prefix factory/toolchain run verify:generated`.
4. Create and run `factory/tests/test-agentos-runtime-adapter.mjs` (or JS test file under `factory/tests/`).
5. Run existing operational and workflow tests (`node factory/tests/test-f7.mjs`, `node factory/tests/test-shutdown.mjs`, `node factory/tests/test-review-adapter.mjs`, `node factory/tests/test-factory-binding-source.mjs`).

---

## Verification Commands & Checkpoints

```bash
# 1. Typecheck TypeScript
npm --prefix factory/toolchain run check

# 2. Build operational bundle
npm --prefix factory/toolchain run build

# 3. Verify generated bundle contract
npm --prefix factory/toolchain run verify:generated

# 4. Run test suite
node factory/tests/test-f7.mjs
node factory/tests/test-shutdown.mjs
node factory/tests/test-review-adapter.mjs
node factory/tests/test-factory-binding-source.mjs
node factory/tests/typescript-factory-operational.mjs
```

---

## Rules & Constraints Compliance
- **Commit Convention**: Follow Conventional Commits (e.g. `refactor: isolate agentos types and events behind agent runtime gateway`).
- **Isolation**: Domain code outside `factory/src/adapters/agentos/` must not import or reference AgentOS DTOs (`CaseEvent`, `AgentFinishedEvent`, `QuestionEvent`, `AgentConfig`, `IntegrationConfig`) or `/api/cases` REST endpoints directly.
- **No AgentOS Modification**: `agentos/**` code untouched.
- **No Dashboard Server Modification**: `factory/dashboard/server.mjs` untouched.
- **Compatibility**: Legacy `agentos.mjs` callers preserve exact signature/behavior.
