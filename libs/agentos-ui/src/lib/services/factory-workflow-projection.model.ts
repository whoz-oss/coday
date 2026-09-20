export type WorkflowProjectionStatus =
  | 'pending'
  | 'ready'
  | 'running'
  | 'waiting_human'
  | 'blocked'
  | 'completed'
  | 'failed'
  | 'cancelled'

export type WorkflowResponsibilityKind = 'human' | 'agent' | 'code'
export interface WorkflowStepResponsibility {
  kind: WorkflowResponsibilityKind
  name?: string
}

export interface WorkflowProjectionStepV1 {
  id: string
  name: string
  status: WorkflowProjectionStatus
  description?: string
  dependsOn: string[]
}

export interface WorkflowProjectionStepV2 extends WorkflowProjectionStepV1 {
  responsibility: WorkflowStepResponsibility
}

/** Canonical projection persisted by the Factory store. expectedRevision is a write precondition, not persisted state. */
export interface WorkflowProjectionV1 {
  schemaVersion: '1'
  workflowId: string
  workflowType: string
  title: string
  status: WorkflowProjectionStatus
  steps: WorkflowProjectionStepV1[]
}

export interface WorkflowProjectionV2 extends Omit<WorkflowProjectionV1, 'schemaVersion' | 'steps'> {
  schemaVersion: '2'
  steps: WorkflowProjectionStepV2[]
}
export type WorkflowProjection = WorkflowProjectionV1 | WorkflowProjectionV2

export type WorkflowControllerExecution =
  | { runtimeId: string; kind: 'agentos'; caseId: string; agentId: string; actorId?: string; observedAt: string }
  | {
      runtimeId: string
      kind: 'coday-express'
      threadId: string
      agentId: string
      actorId?: string
      observedAt: string
    }

export function agentOsControllerUrl(namespaceId: string, execution?: WorkflowControllerExecution): string | undefined {
  return execution?.kind === 'agentos'
    ? `/agentos/home?ns=${encodeURIComponent(namespaceId)}&case=${encodeURIComponent(execution.caseId)}`
    : undefined
}

/** Exact public snapshot returned by list/detail routes. */
export interface WorkflowProjectionSnapshotDto {
  workflowId: string
  revision: number
  projectionHash: string
  controllerExecution?: WorkflowControllerExecution
  projection: WorkflowProjection
}

export type WorkflowProjectionCollectionState = 'active' | 'removed'

export interface WorkflowProjectionListDto {
  data: {
    namespaceId: string
    state: WorkflowProjectionCollectionState
    items: WorkflowProjectionSnapshotDto[]
  }
}

export interface WorkflowProjectionLifecycleDto {
  data: {
    workflowId: string
    namespaceId: string
    state: 'active' | 'removed' | 'purged'
    revision?: number
  }
}

export type WorkflowProjectionEvent =
  | { type: 'open'; namespaceId: string }
  | { type: 'updated'; workflowId: string; namespaceId: string; revision: number }
  | { type: 'removed'; workflowId: string; namespaceId: string }
  | { type: 'restored'; workflowId: string; namespaceId: string; revision: number }
  | { type: 'purged'; workflowId: string; namespaceId: string }

export interface WorkflowStepTimingDto {
  stepId: string
  firstStartedAt?: string
  firstCompletedAt?: string
  lastCompletedAt?: string
  lastTransitionAt?: string
  activeMs: number
  waitingHumanMs: number
  blockedMs: number
  transitionCount: number
  attemptCount: number
  currentStatus: WorkflowProjectionStatus | null
  currentStatusSince: string | null
}
export interface WorkflowTimingDto {
  complete: boolean
  incompleteReasons: string[]
  observedAt?: string
  createdAt?: string
  startedAt?: string
  firstStartedAt?: string
  firstCompletedAt?: string
  lastCompletedAt?: string
  lastActivityAt?: string
  totalElapsedMs: number
  activeMs: number
  waitingHumanMs: number
  blockedMs: number
  transitionCount: number
  currentStatus: WorkflowProjectionStatus | null
  currentStatusSince: string | null
  steps: WorkflowStepTimingDto[]
}
export interface WorkflowProjectionTimingDto {
  data: { namespaceId: string; workflowId: string; timing: WorkflowTimingDto }
}
export interface WorkflowProjectionTimingState {
  revision: number
  timing: WorkflowTimingDto
}

export interface WorkflowProjectionDetailDto {
  data: WorkflowProjectionSnapshotDto & { namespaceId: string }
}

export interface WorkflowHumanInteractionAction {
  id: string
  label: string
  requestedStatus: WorkflowProjectionStatus
}
export interface WorkflowHumanInteraction {
  interactionId: string
  workflowId: string
  stepId: string
  expectedRevision: number
  kind: 'approval' | 'choice' | 'text'
  prompt: string
  actions: WorkflowHumanInteractionAction[]
  openedAt: string
  status: 'open' | 'replied'
}
export interface WorkflowHumanInteractionListDto {
  data: { namespaceId: string; workflowId: string; items: WorkflowHumanInteraction[] }
}
export interface WorkflowHumanReplyDto {
  data: {
    workflowId: string
    interactionId: string
    actorId: string
    evidenceId: string
    revision: number
    projection: WorkflowProjection
    runtimeNotification: 'not-configured'
  }
}

export interface WorkflowProjectionUpdatedEvent {
  workflowId: string
  namespaceId: string
  revision: number
}

/** Persisted journal fact shape. It is not exposed by the current read API. */
export interface WorkflowProjectionJournalFactDto {
  kind: 'projection_created' | 'projection_published'
  revision: number
  projectionHash: string
  changedStepIds: string[]
  timestamp: string
  actorId?: string
  agentId?: string
  caseId?: string
  runId?: string
}
