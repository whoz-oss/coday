export type FactoryDeliveryStage =
  | 'implementation-ready'
  | 'artifact-ready'
  | 'release-approved'
  | 'deployed'
  | 'production-verified'
export type FactoryDeliveryOperationKind =
  | 'deployment'
  | 'production-verification'
  | 'rollback'
  | 'rollback-verification'
export type FactoryDeliveryOperationState = 'pending' | 'running' | 'succeeded' | 'failed' | 'indeterminate'
export type FactoryRollbackRequestStatus = 'requested' | 'approved' | 'rejected'

export interface FactoryDeliveryTargetSummaryDto {
  readonly targetId?: string
  readonly targetHash?: string
  readonly adapterId?: string
}

export interface FactoryDeliveryActorDto {
  readonly actorId?: string
  readonly authorityId?: string
  readonly kind?: string
}

export interface FactoryDeliveryOperationDto {
  readonly operationId: string
  readonly kind: FactoryDeliveryOperationKind
  readonly state: FactoryDeliveryOperationState
  readonly targetRef?: FactoryDeliveryTargetSummaryDto
  readonly attempt: number
  readonly requestedAt: string
  readonly startedAt?: string
  readonly completedAt?: string
  readonly adapterCorrelation?: Readonly<Record<string, unknown>>
  readonly result?: Readonly<Record<string, unknown>>
  readonly error?: Readonly<Record<string, unknown>>
}

export interface FactoryRollbackRequestDto {
  readonly rollbackRequestId: string
  readonly status: FactoryRollbackRequestStatus
  readonly targetId: string
  readonly reasonCode: string
  readonly reason?: string
  readonly requestedAt: string
  readonly requestedBy?: FactoryDeliveryActorDto
  readonly approvedAt?: string
  readonly approvedBy?: FactoryDeliveryActorDto
}

export interface FactoryDeliverySnapshotDto {
  readonly deliveryId: string
  readonly namespaceId: string
  readonly workflowId: string
  readonly environmentId: string
  readonly environmentHash: string
  readonly parentCaseId: string
  readonly runtimeId: string
  readonly branch: string
  readonly baseCommit: string
  readonly headCommit: string
  readonly stage: FactoryDeliveryStage
  readonly revision: number
  readonly git: {
    readonly checkpoint: { readonly commit: string; readonly diffHash: string } | null
    readonly push: { readonly headCommit: string } | null
    readonly pullRequest: {
      readonly id: string
      readonly url: string
      readonly draft: boolean
      readonly state: string
    } | null
  }
  readonly artifact: { readonly state: FactoryDeliveryOperationState | 'pending' }
  readonly release: { readonly state: FactoryDeliveryOperationState | 'pending' }
  readonly deployment: { readonly state: FactoryDeliveryOperationState | 'pending' }
  readonly verification: { readonly state: FactoryDeliveryOperationState | 'pending' }
  readonly blockers: readonly { readonly code: string; readonly message?: string }[]
  readonly deliveryOperations?: readonly FactoryDeliveryOperationDto[]
  readonly unresolvedIndeterminate?: readonly FactoryDeliveryOperationDto[]
  readonly rollbackRequests?: readonly FactoryRollbackRequestDto[]
}

export interface FactoryDeliveryResponseDto {
  readonly data: FactoryDeliverySnapshotDto
}
