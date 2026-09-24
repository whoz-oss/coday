/**
 * Domain vocabulary and port for the agent runtime used by the Factory.
 *
 * This module deliberately knows nothing about AgentOS: no REST endpoint, no
 * transport DTO, no backend-specific event name. Adapters implement the
 * `AgentRuntimeGateway` port and translate their own wire format into the
 * `RuntimeEvent` vocabulary defined here.
 *
 * The Factory orchestrates and measures; the runtime executes agents. The port
 * is the only surface the orchestration layer is allowed to depend on.
 */

/**
 * Branded identifier of a runtime execution.
 *
 * Runtime executions are long-lived: an execution is the conversational context
 * in which a worker runs. It is created, observed, questioned and terminated
 * through the port, never inspected through a transport-specific identifier.
 */
export type RuntimeExecutionId = string & { readonly __brand: 'RuntimeExecutionId' }

/** Builds a branded `RuntimeExecutionId` from a raw transport identifier. */
export function asRuntimeExecutionId(value: string): RuntimeExecutionId {
  return value as RuntimeExecutionId
}

/** Identity of a worker (agent) inside a namespace. */
export interface WorkerIdentity {
  namespaceId: string
  workerName: string
}

/** Integration parameter bag, intentionally open-ended. */
export interface IntegrationParameters {
  rootPath?: string
  readOnly?: boolean
  [key: string]: unknown
}

/** A worker (agent) configuration, as observed by the runtime. */
export interface WorkerConfig {
  name: string
  enabled?: boolean
  subAgents?: string[]
  integrations?: Record<string, unknown> | null
  [key: string]: unknown
}

/** An integration configuration, as observed by the runtime. */
export interface WorkspaceIntegration {
  name: string
  integrationType?: string
  parameters?: IntegrationParameters | null
  [key: string]: unknown
}

/** Minimal case/execution summary returned when an execution is created. */
export interface ExecutionSummary {
  id: string
  [key: string]: unknown
}

/**
 * Result of inspecting whether a worker is usable as a phase role.
 *
 * `reason` is a human-actionable explanation when `ok` is false, `null`
 * otherwise. `worker` is the matched configuration when it exists.
 */
export interface WorkerInspectionResult {
  ok: boolean
  reason: string | null
  worker: WorkerConfig | null
}

/**
 * Result of inspecting the workspace an execution would write to.
 *
 * `rootPath` is the runtime-side root that was compared, `integration` the
 * matched integration when one was resolved.
 */
export interface WorkspaceInspectionResult {
  ok: boolean
  reason: string | null
  rootPath: string | null
  integration?: WorkspaceIntegration | null
}

// --------------------------------------------------------------------------
// Runtime events — the normalized vocabulary the Factory consumes
// --------------------------------------------------------------------------

interface RuntimeEventBase {
  /** Transport-stable identifier used for chronological slicing. */
  id: string
  /** Original wire type, kept for diagnostics only. */
  type: string
  timestamp?: string
}

export interface RuntimeStatusEvent extends RuntimeEventBase {
  kind: 'status'
  status: string
}

export interface RuntimeMessageEvent extends RuntimeEventBase {
  kind: 'message'
  role: string | null
  content: string
}

export interface RuntimeQuestionEvent extends RuntimeEventBase {
  kind: 'question'
  questionId: string
  question: string
}

export interface RuntimeAnswerEvent extends RuntimeEventBase {
  kind: 'answer'
  questionId: string | null
  answer: string | null
}

export interface RuntimeWorkerSelectedEvent extends RuntimeEventBase {
  kind: 'worker_selected'
  workerName: string | null
}

export interface RuntimeWorkerFinishedEvent extends RuntimeEventBase {
  kind: 'worker_finished'
  workerName: string | null
  llmProvider: string | null
  llmModel: string | null
}

export interface RuntimeWorkerRunningEvent extends RuntimeEventBase {
  kind: 'worker_running'
  workerName: string | null
  llmProvider: string | null
  llmModel: string | null
}

export interface RuntimeToolResponseEvent extends RuntimeEventBase {
  kind: 'tool_response'
  toolName: string | null
  success: boolean | null
}

export interface RuntimeOtherEvent extends RuntimeEventBase {
  kind: 'other'
}

/** Discriminated union of every event the Factory understands. */
export type RuntimeEvent =
  | RuntimeStatusEvent
  | RuntimeMessageEvent
  | RuntimeQuestionEvent
  | RuntimeAnswerEvent
  | RuntimeWorkerSelectedEvent
  | RuntimeWorkerFinishedEvent
  | RuntimeWorkerRunningEvent
  | RuntimeToolResponseEvent
  | RuntimeOtherEvent

/** A pending human input request raised by a runtime execution. */
export interface HumanInputRequest {
  questionId: string
  question: string
  timestamp?: string
}

/** LLM model actually used during an execution. */
export interface RuntimeModelUsage {
  agentName: string | null
  llmProvider: string | null
  llmModel: string | null
}

/** Terminal outcome classification of an observed execution. */
export type ExecutionStatus =
  | 'finished'
  | 'pending_question'
  | 'case_busy'
  | 'start_timeout'
  | 'work_timeout'
  | 'killed'
  | 'case_error'
  | 'error'

/**
 * Failure or completion report produced by an observation.
 *
 * Field set is identical whether the execution finished or failed: callers
 * never have to branch on presence, only on `status`.
 */
export interface RuntimeFailure {
  status: ExecutionStatus
  caseStatus: string | null
  message: string
  events: RuntimeEvent[]
  agentsSelected: string[]
  agentTurns: number
  toolCallCount: number
  failedToolCalls: Record<string, number>
  killedByBudget: boolean
  anchored: boolean
  llmModels: RuntimeModelUsage[]
}

/** Observation result of a runtime execution. */
export type ExecutionObservation = RuntimeFailure

/** Reference to a structured result produced by a worker. */
export interface StructuredResultRef {
  attemptId?: string
  [key: string]: unknown
}

/** Channel binding used to route a structured result back into the Factory. */
export interface ResultBinding {
  attemptId?: string
  token?: string
  [key: string]: unknown
}

/** Options accepted when starting a runtime execution. */
export interface ExecutionOptions {
  namespaceId: string
  workerName: string
  brief: string
  caseId?: string
  title?: string
  startTimeoutMs?: number
  workTimeoutMs?: number
}

/** Options accepted when observing a runtime execution. */
export interface ObserveOptions {
  baselineId?: string | null
  startTimeoutMs?: number
  workTimeoutMs?: number
}

/**
 * Port implemented by any agent runtime the Factory drives.
 *
 * The interface is intentionally imperative and execution-centric: it is the
 * smallest surface that lets the orchestration layer start a worker, wait for
 * quiescence, answer a question and stop an execution without knowing anything
 * about the runtime's transport.
 */
export interface AgentRuntimeGateway {
  inspectWorker(namespaceId: string, workerName: string): Promise<WorkerInspectionResult>
  startExecution(options: ExecutionOptions): Promise<RuntimeExecutionId>
  observeExecution(executionId: RuntimeExecutionId, observerOptions?: ObserveOptions): Promise<ExecutionObservation>
  bindResultChannel(executionId: RuntimeExecutionId, binding: ResultBinding): Promise<void>
  answerQuestion(executionId: RuntimeExecutionId, questionId: string, answer: string): Promise<void>
  interruptExecution(executionId: RuntimeExecutionId): Promise<void>
  terminateExecution(executionId: RuntimeExecutionId): Promise<void>
}
