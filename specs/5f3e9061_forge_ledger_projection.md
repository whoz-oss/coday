# Plan: Forge Ledger Read-Only Projection Adapter & Mapping (Step 1)

## Context & Governance
Step 1 of Forge Ledger convergence: Map Forge Ledger JSONL events to Coday Generic Workflow domain constructs via a pure read-only projection adapter, accompanied by documentation and tests.
Authority remains in `forge-ledger` (`factory/lib/forge-ledger.mjs` / `factory/src/domain/forge-bmad/forge-ledger.ts`).

### STRICT SCOPE BOUNDARIES (DO NOT TOUCH):
- DO NOT modify `forge-ledger.mjs` or `forge-ledger.ts`
- DO NOT change the write path or write to generic stores/DBs
- DO NOT modify `FACT_KEYS` in `factory/src/domain/evidence/workflow-evidence.ts` (only document/flag non-whitelisted fields in `docs/forge-ledger-mapping.md`)
- DO NOT touch SQL migrations, SQL adapters, or `factory/infra/migrations/`
- DO NOT touch generated runtime bundle (`factory/runtime/factory-operational.mjs`)
- DO NOT touch `agentos/**`

---

## Proposed Changes & Deliverables

### Deliverable 1: Cartography & Mapping Document
**File**: `docs/forge-ledger-mapping.md`

Create a comprehensive Markdown document with the following structure:
1. **Forge Ledger Event Mapping Table**:
   Exhaustive mapping table for all 11 Forge Ledger event types:
   - `run_started`
   - `story_run_created`
   - `gate_started`
   - `human_decision_recorded`
   - `g2_evaluated`
   - `g2_us_evaluated`
   - `story_analysis_plan_validated`
   - `agent_execution_finished`
   - `story_edit_finished`
   - `story_oracle_finished`
   - `story_g3_evaluated`

   Mapping target columns:
   - **Generic State / Step Transitions (V3)**: WorkflowInstance / WorkflowProjection step statuses (`ready`, `pending`, `running`, `waiting_human`, `blocked`, `completed`, `failed`, `cancelled`).
   - **Generic `workflow_evidence` (V5)**: Kind (`agent-result`, `artifact`, `oracle-result`, `human-decision`), outcome (`pass`, `fail`, `indeterminate`), facts mapped to allowed `FACT_KEYS`, and `source` metadata.
   - **Generic `human_interactions` (V5)**: Interaction opening (`status`: `opening`/`open`, `kind`: `approval`, actions) vs replies (`status`: `replied`, decision outcome).

2. **Explicit Non-Whitelisted Fact Fields Identification**:
   Dedicated section identifying fields in Forge events that are NOT present in the current `FACT_KEYS` whitelist in `workflow-evidence.ts`:
   - `policyVersion`
   - `evidenceSetHash`
   - `requiredDecision`
   - `caseStatus`
   - `killedByBudget`
   - `planSchemaVersion`
   - `filesModified`
   - `filesCreated`
   - `diffValidation`
   - `ownerProjects`
   - `buildHosts`
   - `ownersWithTestTarget`
   - `ownersWithoutTestTarget`
   - `commandHash`
   - `specHash`

3. **Proposals for Step 2 (Without Applying Them)**:
   - Option A: Extend `FACT_KEYS` whitelist with domain-neutral keys or specific keys.
   - Option B: Structured `facts` nesting vs flattening.
   - Option C: Using `source.*` metadata fields for execution context.
   - Option D: Dedicated custom evidence kinds vs standard facts.

4. **Gaps & Ambiguities Identified**:
   - Lack of explicit start events for some sub-steps (e.g. `story_edit_started` vs `story_edit_finished`).
   - Revision tracking differences between Forge `attempt` and Generic `revision`.
   - Handling of multi-story orchestration scope and namespace mapping.

---

### Deliverable 2: Read-Only Projection Adapter
**File**: `factory/src/domain/forge-bmad/forge-ledger-projection.ts`

Implement a pure domain module with zero I/O (`node:fs`, DB, network free; only `node:crypto` allowed if needed).

#### Exported Types:
```typescript
import type { ForgeLedgerEvent } from './types.js'
import type { WorkflowEvidenceInput, WorkflowEvidenceSource } from '../evidence/workflow-evidence.js'
import type { NormalizedHumanInteractionInput } from '../interaction/workflow-human-interaction.js'
import type { WorkflowStatus } from '../workflow/workflow-transition-policy.js'

export interface ForgeGenericEvidenceProjection {
  input: WorkflowEvidenceInput
  source: WorkflowEvidenceSource
  isValid: boolean
  validationError?: string
}

export interface ForgeGenericHumanInteractionProjection {
  type: 'open' | 'reply'
  openInput?: NormalizedHumanInteractionInput
  replyData?: {
    interactionId: string
    actorId: string
    outcome: 'approved' | 'rejected'
    reasonCode: string
    repliedAt: string
  }
}

export interface ForgeGenericTransitionProjection {
  workflowId: string
  stepId: string
  status: WorkflowStatus
  reason: string
  at?: string
}

export interface UnmappedForgeField {
  event: string
  runId?: string
  storyRunId?: string
  field: string
  value: unknown
  reason: 'not_in_fact_whitelist' | 'unmapped_event_type' | 'unsupported_structure'
}

export interface ForgeLedgerGenericProjectionResult {
  evidences: ForgeGenericEvidenceProjection[]
  interactions: ForgeGenericHumanInteractionProjection[]
  transitions: ForgeGenericTransitionProjection[]
  unmappedEvents: UnmappedForgeField[]
}
```

#### Main Function Signature:
```typescript
export function projectForgeLedgerToGeneric(
  events: readonly ForgeLedgerEvent[]
): ForgeLedgerGenericProjectionResult
```

#### Mapping Logic Details:
1. **Transitions**:
   - `run_started`: maps `EpicRun` workflowId = `runId` to state `ready`.
   - `story_run_created`: maps story workflowId = `runId` to `ready` or `pending`.
   - `gate_started` (G1): transitions Epic run step `G1` to `waiting_human`.
   - `human_decision_recorded`: transitions `G1` step based on decision (approved -> `completed`, rejected -> `failed`).
   - `g2_evaluated`: transitions Epic run step `G2` (passed -> `completed`, failed -> `failed`).
   - `g2_us_evaluated`: transitions Story step `G2-US` (passed -> `completed`, failed -> `failed`).
   - `agent_execution_finished`: transitions Story execution step (`running` -> `completed` / `blocked` / `failed`).
   - `story_edit_finished`: transitions Story edit step (finished -> `completed`, failed -> `failed`).
   - `story_oracle_finished` / `story_g3_evaluated`: transitions Story oracle step (passed -> `completed`, failed -> `failed`).

2. **Evidences (`WorkflowEvidenceInput` & Validation)**:
   - For mapped events, build candidate `WorkflowEvidenceInput` (e.g. `kind: 'agent-result'`, `human-decision`, `oracle-result`, `artifact`).
   - Populate `facts` using ONLY allowed keys in `FACT_KEYS` (`attempt`, `durationMs`, `exitCode`, `resultCode`, `decisionTextHash`, etc.).
   - Pass candidate input to `validateWorkflowEvidenceInput(candidate, workflowId)`.
   - Record validation result (`isValid: boolean`, `validationError?: string`).
   - Derive `source` (`kind`: `forge-ledger`, `agentId`, `actorId`, `caseId`, etc.).

3. **Human Interactions**:
   - `gate_started` (G1): maps to interaction opening (`kind: 'approval'`, actions `[approve, reject]`).
   - `human_decision_recorded`: maps to interaction reply with `actorId`, `outcome`, `reasonCode`.

4. **Unmapped Events & Fields (`unmappedEvents`)**:
   - Track every event attribute not captured by generic structures or strictly excluded from `FACT_KEYS`.
   - Non-whitelisted fields (e.g. `policyVersion`, `evidenceSetHash`, `filesModified`, `commandHash`, `specHash`, `buildHosts`, etc.) are recorded in `unmappedEvents` with reason `not_in_fact_whitelist`.

---

### Deliverable 3: Comprehensive Test Suite
**File**: `factory/tests/test-forge-ledger-projection.mjs`

Runner execution pattern (compatible with project ESM ts-resolve hook):
`node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/test-forge-ledger-projection.mjs`

#### Test Coverage:
1. **Offline test (Exit 0 on success)**.
2. **Synthetic / Fixture Test Cases**:
   - Test `projectForgeLedgerToGeneric` on a complete lifecycle sequence of events: `run_started`, `story_run_created`, `gate_started`, `human_decision_recorded`, `g2_evaluated`, `g2_us_evaluated`, `agent_execution_finished`, `story_analysis_plan_validated`, `story_edit_finished`, `story_oracle_finished`, `story_g3_evaluated`.
   - Verify generated evidence list, ensuring `validateWorkflowEvidenceInput` returns `ok: true` for all validated evidence items.
   - Verify generic human interactions (open and reply).
   - Verify workflow step transitions state progression.
   - Verify `unmappedEvents` explicitly contains all non-whitelisted Forge fields (`policyVersion`, `evidenceSetHash`, `filesModified`, `commandHash`, `specHash`, etc.).
3. **Integration with `createEpicRun` / `parseForgeLedger`**:
   - Generate events dynamically using `createEpicRun` in a tmp directory (reusing pattern from `test-forge-run.mjs`), run `projectForgeLedgerToGeneric` on parsed events, and assert validity.

---

## Verification & Quality Strategy

1. Execute test suite for the new adapter:
   `node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/test-forge-ledger-projection.mjs`
2. Run related existing tests to ensure zero regression:
   - `node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/test-forge-run.mjs`
   - `node --import ./factory/tests/support/node-ts-resolve-hook.mjs factory/tests/test-workflow-evidence.mjs`
3. Run affected Nx tests:
   `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
