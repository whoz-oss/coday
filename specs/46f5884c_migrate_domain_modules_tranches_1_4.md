# Plan: Migrate Domain Modules (Tranches 1–4)

## Architecture & Goal

Migrate pure workflow domain modules into strict TypeScript bounded contexts under `factory/src/domain/`:
1. **Tranche 1 (Workflow Definition & Instance)**: `factory/src/domain/workflow/workflow-definition.ts` and `workflow-instance.ts` (already created, re-export verification/type refinement required).
2. **Tranche 2 (Workflow Transition Policy)**: `factory/src/domain/workflow/workflow-transition-policy.ts` (pure transition evaluation, validation, semantic & scope hashing, status state machines).
3. **Tranche 3 (Workflow Evidence)**: `factory/src/domain/evidence/workflow-evidence.ts` (pure evidence input validation, constants, and canonical data models).
4. **Tranche 4 (Workflow Human Interaction)**: `factory/src/domain/interaction/workflow-human-interaction.ts` (pure interaction validation, semantic hashing, interaction constants, and canonical data types).

### Key Architectural Constraints
- `factory/src/domain/` **MUST NOT** import `node:fs`, `node:http`, AgentOS, or Git CLI. Only pure domain logic (`node:crypto` is allowed for hashing/UUIDs).
- `factory/lib/*.mjs` legacy files MUST remain intact as facades delegating to the generated operational bundle (`factory/runtime/factory-operational.mjs`) or domain re-exports.
- `factory/src/entrypoints/factory-operational.ts` MUST export all domain functions and types so they are bundled into `factory/runtime/factory-operational.mjs`.
- Clean up any stray test runs/output files in `factory/runs/` created during verification.

---

## Detailed Step-by-Step Execution Plan

### Step 1: Verify & Refine Tranche 1 (`workflow-definition.ts` & `workflow-instance.ts`)

1. Check existing `factory/src/domain/workflow/workflow-definition.ts` and `workflow-instance.ts`.
2. Verify exports in `factory/src/entrypoints/factory-operational.ts`:
   - Re-exports `* from '../domain/workflow/workflow-definition.js'`
   - Re-exports `* from '../domain/workflow/workflow-instance.js'`
3. Verify legacy facades:
   - `factory/lib/workflow-definition.mjs` re-exports from `../runtime/factory-operational.mjs`.
   - `factory/lib/workflow-instance.mjs` re-exports from `../runtime/factory-operational.mjs`.

### Step 2: Implement Tranche 2 (`factory/src/domain/workflow/workflow-transition-policy.ts`)

1. Create `factory/src/domain/workflow/workflow-transition-policy.ts`:
   - Port logic from `factory/lib/workflow-transition-policy.mjs`:
     - Constants: `WORKFLOW_STATUSES`, `WORKFLOW_TRANSITIONS`.
     - Request validation: `validateWorkflowTransitionRequest(input, expectedWorkflowId)`.
     - Hashing: `transitionSemanticHash(request)`, `transitionScopeHash(namespaceId, request, execution)`.
     - Policy evaluation:
       - `evaluateWorkflowTransition({ request, snapshot, definition, evidence, execution })`
       - `evaluateHumanCheckpointOpen({ request, snapshot, definition, execution })`
       - `evaluateHumanResolutionTransition({ request, snapshot, definition, evidence, execution })`
       - `applyWorkflowTransition(snapshot, definition, request, timestamp)`
   - Strictly type all parameters, snapshot objects, definitions, evidence arrays, execution contexts, and return types (`TransitionDecision`).
   - Pure domain imports only (`node:crypto` for `randomUUID` and `createHash`). No I/O imports.
2. Update `factory/src/entrypoints/factory-operational.ts`:
   - Add `export * from '../domain/workflow/workflow-transition-policy.js'`
3. Refactor `factory/lib/workflow-transition-policy.mjs`:
   - Convert to a facade re-exporting all exported functions/constants from `../runtime/factory-operational.mjs`.

### Step 3: Implement Tranche 3 (`factory/src/domain/evidence/workflow-evidence.ts`)

1. Create `factory/src/domain/evidence/workflow-evidence.ts`:
   - Port logic from `factory/lib/workflow-evidence.mjs`:
     - Constants: `WORKFLOW_EVIDENCE_KINDS`, `WORKFLOW_EVIDENCE_OUTCOMES`, `WORKFLOW_EVIDENCE_LIMITS`.
     - Input validation: `validateWorkflowEvidenceInput(input, expectedWorkflowId)`.
   - Add comprehensive TypeScript interfaces: `WorkflowEvidenceInput`, `WorkflowEvidence`, `ValidateEvidenceResult`, etc.
   - Pure domain imports only (`node:crypto` for `randomUUID` if needed).
2. Update `factory/src/entrypoints/factory-operational.ts`:
   - Add `export * from '../domain/evidence/workflow-evidence.js'`
3. Refactor `factory/lib/workflow-evidence.mjs`:
   - Convert to a facade re-exporting from `../runtime/factory-operational.mjs`.

### Step 4: Implement Tranche 4 (`factory/src/domain/interaction/workflow-human-interaction.ts`)

1. Create `factory/src/domain/interaction/workflow-human-interaction.ts`:
   - Extract pure domain constants, types, validation, and semantic hashing from `factory/lib/workflow-human-interaction-store.mjs`:
     - Constants: interaction kinds (`approval`, `choice`, `text`), status lists.
     - Types: `HumanInteractionInput`, `HumanInteractionRecord`, `HumanInteractionEvent`, `OpenedRevisionHelper`.
     - Helpers: `canonicalHumanInteractionInput(value)`, `humanInteractionSemanticHash(input)`.
   - Pure domain imports only (`node:crypto`). No `node:fs` or file I/O!
2. Update `factory/src/entrypoints/factory-operational.ts`:
   - Add `export * from '../domain/interaction/workflow-human-interaction.js'`
3. Update `factory/lib/workflow-human-interaction-store.mjs`:
   - Import pure domain helpers/types from `../runtime/factory-operational.mjs` (or re-export them for legacy consumers).
   - Keep file I/O store methods (`WorkflowHumanInteractionStore`, `append`, `path`, `events`, `list`, `reconcileOpen`) in `workflow-human-interaction-store.mjs` (store layer), delegating pure hashing/types to domain.

### Step 5: Build, Typecheck, and Test Verification

1. Rebuild operational bundle:
   `node factory/toolchain/build.mjs`
2. Run strict TypeScript typecheck:
   `cd factory/toolchain && npm run typecheck`
3. Run factory unit tests:
   `node factory/tests/test-workflow-definition.mjs`
   `node factory/tests/test-workflow-instance.mjs`
   `node factory/tests/test-workflow-transition-policy.mjs`
   `node factory/tests/test-workflow-evidence.mjs`
   `node factory/tests/test-workflow-human-interaction-source.mjs`
   `node factory/tests/test-workflow-human-interaction-revision.mjs`
   `node factory/tests/test-deterministic-oracle-source.mjs`
   `node factory/tests/test-delivery-phase9.mjs`
4. Clean up stray test artifact files in `factory/runs/` if generated.

---

## Verification Criteria

- All files in `factory/src/domain/` contain NO imports of `fs`, `http`, `agentos`, or `git`.
- `cd factory/toolchain && npm run typecheck` passes with zero errors.
- Factory unit tests execute successfully with zero regressions.
- Legacy files in `factory/lib/` remain backwards compatible facades.
