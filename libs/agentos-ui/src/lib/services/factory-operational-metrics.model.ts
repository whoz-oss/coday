export type FactoryMetricsScope = 'self' | 'descendants'

export interface FactoryMetricAvailability<T> {
  available: boolean
  complete: boolean
  reasons: string[]
  sourceCategories: string[]
  value?: T
}

export interface FactoryDurationInterval {
  startAt: string
  endAt: string
  durationMs: number
}

export interface FactoryCycleTimeValue extends FactoryDurationInterval {
  semantics: 'workflow_interval'
  envelope?: never
  unionDurationMs?: never
}

export interface FactoryDescendantCycleTimeValue {
  semantics: 'calendar_envelope_and_interval_union'
  envelope: FactoryDurationInterval
  unionDurationMs: number
  mergedIntervals: FactoryDurationInterval[]
}

export interface FactoryReviewTimeValue {
  semantics: 'completed_approval_interval_union'
  durationMs: number
  mergedIntervals: FactoryDurationInterval[]
  completedInteractionCount: number
  unresolvedInteractions: Array<{ interactionId: string; openedAt: string }>
}

export interface FactoryCurrentWipValue {
  semantics: 'unique_workflows_in_non_terminal_states'
  count: number
  byState: Record<string, number>
}

export interface FactoryDeliveryIntervalValue {
  semantics: string
  intervals: Array<FactoryDurationInterval & { deliveryId: string; endpoint?: string }>
}

export interface FactoryUnavailableCapability {
  available: false
  reason: string
}

export interface FactoryOperationalMetricsResponseDto {
  schemaVersion: '1'
  observedAt: string
  scope: { kind: FactoryMetricsScope; workflowId: string; includedWorkflowIds: string[] }
  metrics: {
    cycleTime: FactoryMetricAvailability<FactoryCycleTimeValue | FactoryDescendantCycleTimeValue>
    reviewTime: FactoryMetricAvailability<FactoryReviewTimeValue>
    currentWip: FactoryMetricAvailability<FactoryCurrentWipValue>
    deploymentDelay: FactoryMetricAvailability<FactoryDeliveryIntervalValue>
    workflowCreatedToProductionVerified: FactoryMetricAvailability<FactoryDeliveryIntervalValue>
  }
  capabilities: {
    llmUsage: FactoryUnavailableCapability
    cost: FactoryUnavailableCapability
    dora: FactoryUnavailableCapability
    rollbackRate: FactoryUnavailableCapability
  }
}
