export type WorkflowProjectionStatus =
  | 'pending'
  | 'ready'
  | 'running'
  | 'waiting_human'
  | 'blocked'
  | 'completed'
  | 'failed'
  | 'cancelled'

export interface WorkflowProjectionStepV1 {
  id: string
  name: string
  status: WorkflowProjectionStatus
  description?: string
  dependsOn: string[]
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

/** Exact public snapshot returned by list/detail routes. */
export interface WorkflowProjectionSnapshotDto {
  workflowId: string
  revision: number
  projectionHash: string
  projection: WorkflowProjectionV1
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

export interface WorkflowProjectionDetailDto {
  data: WorkflowProjectionSnapshotDto & { namespaceId: string }
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
