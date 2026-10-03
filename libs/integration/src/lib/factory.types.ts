export type CodayExpressExecution = {
  namespaceId: string
  runtimeId: string
  kind: 'coday-express'
  agentId: string
  threadId: string
  actorId?: string
}
export type Responsibility = { kind: 'human' | 'agent' | 'code'; name?: string }
export type Step = {
  id: string
  name: string
  status: string
  description?: string
  dependsOn?: string[]
  responsibility?: Responsibility
}
export type WorkflowProjection = {
  schemaVersion: '1' | '2'
  workflowId: string
  workflowType: string
  title: string
  status: string
  expectedRevision?: number
  steps: Step[]
}
