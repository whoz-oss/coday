/**
 * Public facade for the mappers module.
 *
 * All exports that were previously in `core/mappers.ts` are re-exported here
 * so existing imports (`from './mappers'` or `from '../core/mappers'`) continue
 * to work without change. The facade adds no logic — it is purely structural.
 */

// Primitive helpers (used by tests and other modules)
export { toEpochMs } from './utils'

// Step classification
export type { ActorKind, LaneDescriptor, StepState } from './step-classification'
export {
  classifyActorKind,
  actorName,
  classifyLane,
  resolveStepState,
  stepDurationSec,
  toneForActor,
} from './step-classification'

// Actions & cost
export type { RealCost } from './actions'
export {
  extractRealCost,
  extractInteractions,
  extractAttempts,
  extractAllowedActions,
  extractBlockers,
} from './actions'

// Timeline
export type { ControllerRequest } from './timeline'
export {
  mapWorkflowStateToRunStatus,
  mapStepsToPhaseSegments,
  mapProjectionToLanes,
  mapProjectionToRunSummary,
  timelineSteps,
  readControllerRequest,
} from './timeline'

// Phase detail
export {
  buildPhaseDetail,
  mapProjectionToPhaseByStepId,
  mapEvidenceToEvents,
  mapInteractionsToEvents,
  sumFacts,
  filterEventsForSelection,
} from './phase-detail'

// Workflow / session
export {
  mapProjectionToSessionDetail,
  mapProjectionToFactoryRun,
  workflowIdOf,
  namespaceOf,
  controllerCaseIdOf,
  deriveRunStatus,
} from './workflow'
