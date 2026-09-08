import { EntityMetadata } from './entity-metadata'

export interface SubCaseStartedEvent {
  caseId: string
  id: string
  metadata: EntityMetadata
  namespaceId: string
  timestamp: string
  type: 'SubCaseStartedEvent'
  delegationId: string
  toolRequestId: string
  subCaseId: string
  agentName: string
  task: string
  resumed: boolean
}
