/**
 * Private wire DTOs for the AgentOS REST API.
 *
 * These types are an implementation detail of the AgentOS adapter. They MUST
 * NOT be imported outside `factory/src/adapters/agentos/`: the rest of the
 * Factory consumes the transport-agnostic vocabulary from
 * `../../ports/agent-runtime-gateway.js`.
 */

/** Case resource as returned by `/api/cases`. */
export interface CaseDTO {
  id: string
  namespaceId?: string
  title?: string
  status?: string
  [key: string]: unknown
}

/** Base shape shared by every case event returned by `/api/case-events`. */
export interface CaseEventDTO {
  id: string
  type: string
  timestamp?: string
  [key: string]: unknown
}

export interface CaseStatusEventDTO extends CaseEventDTO {
  type: 'CaseStatusEvent'
  status: string
}

export interface MessageContentDTO {
  content?: string
  [key: string]: unknown
}

export interface MessageEventDTO extends CaseEventDTO {
  type: 'MessageEvent'
  actor?: { role?: string; [key: string]: unknown }
  content?: MessageContentDTO[] | string | null
}

export interface QuestionEventDTO extends CaseEventDTO {
  type: 'QuestionEvent'
  question?: string
}

export interface AnswerEventDTO extends CaseEventDTO {
  type: 'AnswerEvent'
  questionId?: string
  answer?: string
}

export interface AgentSelectedEventDTO extends CaseEventDTO {
  type: 'AgentSelectedEvent'
  agentName?: string
}

export interface AgentFinishedEventDTO extends CaseEventDTO {
  type: 'AgentFinishedEvent'
  agentName?: string
  llmProvider?: string | null
  llmModel?: string | null
}

export interface AgentRunningEventDTO extends CaseEventDTO {
  type: 'AgentRunningEvent'
  agentName?: string
  llmProvider?: string | null
  llmModel?: string | null
}

export interface ToolResponseEventDTO extends CaseEventDTO {
  type: 'ToolResponseEvent'
  toolName?: string
  success?: boolean
}

/** Agent configuration as returned by `/api/agent-configs`. */
export interface AgentConfigDTO {
  name: string
  enabled?: boolean
  subAgents?: string[]
  integrations?: Record<string, unknown> | null
  [key: string]: unknown
}

/** Integration configuration as returned by `/api/integration-configs`. */
export interface IntegrationConfigDTO {
  name: string
  integrationType?: string
  parameters?: IntegrationConfigParametersDTO | null
  [key: string]: unknown
}

export interface IntegrationConfigParametersDTO {
  rootPath?: string
  readOnly?: boolean
  [key: string]: unknown
}
