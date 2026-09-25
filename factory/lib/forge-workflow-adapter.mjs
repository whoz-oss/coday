// Stateless compatibility facade. The Forge workflow projection adapter lives
// only in the generated operational bundle, built from the TypeScript source
// `factory/src/domain/forge-bmad/forge-workflow-adapter.ts`.
export {
  FORGE_WORKFLOW_ERROR_CODES,
  adaptForgeRunToWorkflowProjection,
} from '../runtime/factory-operational.mjs'
