// Stateless compatibility facade. Status machines, request validation and
// transition evaluation live only in the generated operational bundle, built
// from the TypeScript source
// `factory/src/domain/workflow/workflow-transition-policy.ts`.
export {
  WORKFLOW_STATUSES,
  WORKFLOW_TRANSITIONS,
  validateWorkflowTransitionRequest,
  transitionSemanticHash,
  transitionScopeHash,
  evaluateHumanCheckpointOpen,
  evaluateHumanResolutionTransition,
  evaluateWorkflowTransition,
  applyWorkflowTransition,
  applyHumanCheckpointOpen,
} from '../runtime/factory-operational.mjs'
