// Stateless compatibility facade. Validation, canonicalization and hashing live
// only in the generated operational bundle, built from the TypeScript source
// `factory/src/domain/workflow/workflow-definition.ts`.
export {
  WORKFLOW_DEFINITION_SCHEMA_VERSION,
  WORKFLOW_DEFINITION_RESPONSIBILITIES,
  WORKFLOW_DEFINITION_ERROR_CODES,
  validateWorkflowDefinition,
  canonicalizeWorkflowDefinition,
  hashWorkflowDefinition,
} from '../runtime/factory-operational.mjs'
