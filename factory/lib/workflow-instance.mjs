// Stateless compatibility facade. Start-command hashing and instance creation
// live only in the generated operational bundle, built from the TypeScript
// source `factory/src/domain/workflow/workflow-instance.ts`.
export {
  WORKFLOW_GOVERNANCE_MODE,
  workflowStartCommandHash,
  createWorkflowInstance,
} from '../runtime/factory-operational.mjs'
