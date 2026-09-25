import { EntityMetadata } from './entity-metadata'

export interface SubCaseFinishedEvent {
  caseId: string
  id: string
  metadata: EntityMetadata
  namespaceId: string
  timestamp: string
  type: 'SubCaseFinishedEvent'
  delegationId: string
  toolRequestId: string
  subCaseId: string
  agentName: string
  outcome: 'SUCCESS' | 'WAITING_USER' | 'ERROR' | 'TIMEOUT' | 'KILLED'
  errorType?: string
}
