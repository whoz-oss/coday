export type FactoryDeliveryStage =
  | 'implementation-ready'
  | 'artifact-ready'
  | 'release-approved'
  | 'deployed'
  | 'production-verified'
export type FactoryDeliveryOperationState = 'pending' | 'running' | 'succeeded' | 'failed' | 'indeterminate'

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
}

export interface FactoryDeliveryResponseDto {
  readonly data: FactoryDeliverySnapshotDto
}
