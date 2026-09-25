// Stateless compatibility facade. Evidence kinds, limits, validation and
// record materialization live only in the generated operational bundle, built
// from the TypeScript source `factory/src/domain/evidence/workflow-evidence.ts`.
export {
  WORKFLOW_EVIDENCE_KINDS,
  WORKFLOW_EVIDENCE_OUTCOMES,
  WORKFLOW_EVIDENCE_LIMITS,
  validateWorkflowEvidenceInput,
  createWorkflowEvidence,
} from '../runtime/factory-operational.mjs'
