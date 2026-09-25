# Plan: Migrate Tranche 1 Workflow Domain to TypeScript

## Overview
Migrate tranche 1 domain modules (`workflow-definition.mjs` and `workflow-instance.mjs`) to pure TypeScript under `factory/src/domain/workflow/`. Ensure strict TypeScript compilation via `factory/toolchain` and ensure zero runtime behavior changes or broken legacy callers by delegating from `factory/lib/workflow-definition.mjs` and `factory/lib/workflow-instance.mjs` to the new TS domain implementation.

---

## User Review Required

> [!IMPORTANT]
> - No functional, persistent format, or API contract changes will be introduced.
> - Pure domain rule: `factory/src/domain/workflow/` will not import Node standard library I/O modules (`node:fs`, etc.), HTTP libraries, AgentOS, or Git CLI. It will only import `node:crypto` for SHA-256 hashing.
> - Existing tests in `factory/tests/test-workflow-definition.mjs` and `factory/tests/test-workflow-instance.mjs` (and all dependent tests in `factory/`) must pass cleanly.

---

## Proposed Changes

### Domain Module 1: Workflow Definition (`factory/src/domain/workflow/workflow-definition.ts`)

#### Pure Logic & Types
Define strict TypeScript interfaces, types, constants, and functions corresponding to `factory/lib/workflow-definition.mjs`:
- `WORKFLOW_DEFINITION_SCHEMA_VERSION = '1'`
- `WORKFLOW_DEFINITION_RESPONSIBILITIES = ['human', 'agent', 'code'] as const`
- `ResponsibilityKind = typeof WORKFLOW_DEFINITION_RESPONSIBILITIES[number]`
- `WORKFLOW_DEFINITION_ERROR_CODES`:
  - `INVALID_DEFINITION`, `INVALID_SCHEMA_VERSION`, `INVALID_VALUE`, `DUPLICATE_STEP_ID`, `MISSING_DEPENDENCY`, `SELF_DEPENDENCY`, `DEPENDENCY_CYCLE`, `INVALID_RESPONSIBILITY`
- Types:
  - `WorkflowStepResponsibility`: `{ kind: ResponsibilityKind; name: string }`
  - `WorkflowStepDefinition`: `{ id: string; name: string; responsibility: WorkflowStepResponsibility; dependsOn: string[] }`
  - `TrustedExecutionConfig`: `{ allowedPaths: string[] }`
  - `WorkflowDefinition`: `{ schemaVersion: string; workflowType: string; version: string; title: string; trustedExecution?: TrustedExecutionConfig; steps: WorkflowStepDefinition[] }`
  - `WorkflowDefinitionError`: `{ code: string; path: string; details?: Record<string, unknown> }`
  - `ValidateWorkflowDefinitionResult`: `{ ok: true; definition: WorkflowDefinition } | { ok: false; error: WorkflowDefinitionError }`
- Functions:
  - `validateWorkflowDefinition(input: unknown): ValidateWorkflowDefinitionResult`
  - `canonicalizeWorkflowDefinition(definition: unknown): string`
  - `hashWorkflowDefinition(definition: unknown): string`

#### Validation Logic
Port exact validation rules:
- Object validation, unexpected top-level fields against `DEFINITION_FIELDS`.
- `schemaVersion === '1'`.
- Safe ID checks (REGEX `/^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/`) for `workflowType`, `step.id`, `step.dependsOn`.
- SemVer regex `/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/` for `version`.
- `title` non-empty string <= 256 chars.
- `trustedExecution` validation (allowedPaths non-empty array of valid relative paths with no `..`, leading `/`, `\`, or `\0`).
- `steps` array check (1 to 500 steps).
- Individual step validation (`id`, `name`, `responsibility`, `dependsOn`).
- Duplicate step ID check.
- Self-dependency and missing dependency checks.
- Cycle detection using graph depth-first traversal with `visiting` and `visited` sets.

---

### Domain Module 2: Workflow Instance (`factory/src/domain/workflow/workflow-instance.ts`)

#### Pure Logic & Types
Define strict TypeScript interfaces, types, constants, and functions corresponding to `factory/lib/workflow-instance.mjs`:
- `WORKFLOW_GOVERNANCE_MODE = 'governed'`
- Types:
  - `WorkflowStartCommand`: `{ workflowId: string; workflowType: string; title: string; relations?: Record<string, unknown> }`
  - `WorkflowDefinitionInput`: `{ workflowType: string; version: string; definitionHash: string; steps: WorkflowStepDefinition[] }`
  - `ControllerExecutionInput`: `{ runtimeId: string; kind: string; agentId: string; caseId: string; actorId: string }`
  - `WorkflowInstanceStep`: `{ id: string; status: 'ready' | 'pending' }`
  - `WorkflowProjectionStep`: `{ id: string; name: string; status: 'ready' | 'pending'; dependsOn: string[]; responsibility: WorkflowStepResponsibility }`
  - `WorkflowInstance`:
    ```ts
    {
      governanceMode: typeof WORKFLOW_GOVERNANCE_MODE
      workflowId: string
      workflowType: string
      definitionVersion: string
      definitionHash: string
      revision: number
      title: string
      status: 'ready'
      steps: WorkflowInstanceStep[]
      relations: Record<string, unknown>
      controllerExecution: ControllerExecutionInput & { observedAt: string }
      environmentRef: null
      deliveryRef: null
      createdAt: string
      updatedAt: string
    }
    ```
  - `WorkflowProjection`:
    ```ts
    {
      schemaVersion: '2'
      workflowId: string
      workflowType: string
      title: string
      status: 'ready'
      steps: WorkflowProjectionStep[]
    }
    ```
  - `CreateWorkflowInstanceResult`:
    ```ts
    {
      instance: WorkflowInstance
      projection: WorkflowProjection
      creationCommandHash: string
    }
    ```
- Functions:
  - `workflowStartCommandHash(command: WorkflowStartCommand, definition: WorkflowDefinitionInput): string`
  - `createWorkflowInstance(command: WorkflowStartCommand, definition: WorkflowDefinitionInput, controllerExecution: ControllerExecutionInput, observedAt?: string): CreateWorkflowInstanceResult`

#### Relations Dependency Handling
Note: `workflowStartCommandHash` and `createWorkflowInstance` fall back to `independentWorkflowRelations(command.workflowId)` if `command.relations` is omitted.
To keep domain pure and avoid cyclic dependencies, `independentWorkflowRelations` is implemented in `workflow-instance.ts` or imported from a pure helper if needed. In `lib/workflow-relations.mjs`, `independentWorkflowRelations(workflowId)` returns `{ rootWorkflowId: workflowId }`.

---

### Facades / Re-exports in `factory/lib/`

1. `factory/lib/workflow-definition.mjs`:
   - Replace legacy implementation with delegation/re-export to compiled/source JS or TS module (Node ES module resolution).
   - Re-export `WORKFLOW_DEFINITION_SCHEMA_VERSION`, `WORKFLOW_DEFINITION_RESPONSIBILITIES`, `WORKFLOW_DEFINITION_ERROR_CODES`, `validateWorkflowDefinition`, `canonicalizeWorkflowDefinition`, `hashWorkflowDefinition`.

2. `factory/lib/workflow-instance.mjs`:
   - Re-export `WORKFLOW_GOVERNANCE_MODE`, `workflowStartCommandHash`, `createWorkflowInstance` from the TS domain implementation.

---

## Verification Plan

### Automated Verification
1. Strict TypeScript check:
   ```bash
   cd factory/toolchain && npm run typecheck
   ```
   Must complete with exit code 0 and no type errors.

2. Node test suite verification:
   ```bash
   node factory/tests/test-workflow-definition.mjs
   ```
   And run all factory tests that exercise workflow definition/instance logic:
   ```bash
   node factory/tests/test-workflow-definition-api.mjs
   node factory/tests/test-workflow-relations.mjs
   node factory/tests/test-workflow-code-transition.mjs
   node factory/tests/test-workflow-transition-policy.mjs
   node factory/tests/test-workflow-projection.mjs
   ```

3. Verification of build and lint:
   ```bash
   pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
   pnpm nx affected -t build --base="$(cat /work/data/baseline)"
   ```
