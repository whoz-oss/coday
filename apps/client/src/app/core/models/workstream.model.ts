/**
 * Workstream Cockpit DTO contracts.
 *
 * Mirrors the Phase 0 DTO contracts defined in
 * `app_docs/workstream_agent_cartography_and_contracts.md` (§6).
 * Keep these interfaces in sync with the Factory — they are the exact wire
 * shapes the Workstream Agent tools and `/api/factory/**` endpoints produce.
 *
 * NOTE: the Factory wraps every payload in `{ data: ... }` on success and
 * `{ error: { code, message, details } }` on error (§2.3, §7). The types below
 * model the UNWRAPPED `data` payload that a client consumes after unwrapping.
 */

/** §6.1 get_step_attempts — durable attempt status enum. */
export type AttemptStatus =
  | 'pending'
  | 'claiming'
  | 'starting'
  | 'running'
  | 'waiting_human'
  | 'succeeded'
  | 'failed'
  | 'indeterminate'
  | 'interrupted'

/** §6.1 get_blockers — blocker code enum. */
export type BlockerCode =
  | 'WAITING_HUMAN_INTERACTION'
  | 'STEP_BLOCKED'
  | 'ATTEMPT_FAILED'
  | 'REAL_COST_PAUSED'
  | 'VERIFICATION_FAILED'
  | 'UNKNOWN_RUNTIME'

/**
 * §6.1 get_workflow — projection lifecycle state enum, extended with the Phase 10
 * terminal/sealing vocabulary.
 *
 * `completed` = executed successfully (Factory sealed); `archived` = retired from the
 * active surface but still readable; `runtime-closed` = the AgentOS runtime closed the
 * case without a Factory success verdict (e.g. killed/closed-by-user). The cockpit MUST
 * render these distinctly — they are NOT interchangeable.
 */
export type WorkflowState = 'absent' | 'existing' | 'removed' | 'purged' | 'completed' | 'archived' | 'runtime-closed'

/**
 * Stable `type` values of an {@link AllowedActionDto} (Factory `WorkflowActionTypes`).
 * The cockpit derives its controls strictly from these — never from a hardcoded list.
 */
export type AllowedActionType = 'reply' | 'retry' | 'cancel_attempt' | 'continue_cost' | 'stop_cost'

/** §6.1 get_required_human_actions — allowed human action ids. */
export type HumanActionId = 'approve' | 'reject'

/** §6.2 propose_plan_change — plan operation enum. */
export type PlanChangeOp = 'add_step' | 'remove_step' | 'reorder_step' | 'change_responsibility'

/** §6.1 get_workstream output. */
export interface WorkstreamDto {
  workstreamId: string
  organizationId: string
  name: string
  status: string
  revision: number
}

/** §6.1 list_workflows output item. */
export interface WorkflowSummaryDto {
  workflowId: string
  workflowType: string
  title: string
  status: string
  revision: number
}

/** §6.1 list_workflows output envelope (data payload). */
export interface WorkflowListDto {
  items: WorkflowSummaryDto[]
  nextCursor: string | null
}

/** §6.1 get_workflow — step state. */
export interface WorkflowStepDto {
  stepId: string
  status: string
  revision: number
}

/** §6.1 get_blockers output item (also embedded in get_workflow). */
export interface WorkflowBlockerDto {
  code: BlockerCode
  stepId: string | null
  message: string
}

/** §6.1 get_workflow output. */
export interface WorkflowDetailDto {
  state: WorkflowState
  workflowId: string
  revision: number
  workflowType: string
  status: string
  steps: WorkflowStepDto[]
  blockers: WorkflowBlockerDto[]
}

/** §6.1 get_step_attempts output item (derived from DurableAgentAttemptDto, secrets stripped). */
export interface DurableAgentAttemptDto {
  attemptId: string
  stepId: string
  attemptNumber: number
  agentName: string
  status: AttemptStatus
  caseId: string
  failureCode: string | null
  resultEvidenceId: string | null
  revision: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

/** Alias of DurableAgentAttemptDto used by the step-attempts view. */
export type StepAttemptDto = DurableAgentAttemptDto

/** §6.1 get_required_human_actions — one response option. */
export interface HumanActionDto {
  id: HumanActionId
  label: string
}

/** §6.1 get_required_human_actions output item (open interaction + allowed actions). */
export interface HumanActionRequiredDto {
  interactionId: string
  stepId: string
  questionEventId: string | null
  prompt: string
  actions: HumanActionDto[]
  expectedRevision: number
}

/** Alias of HumanActionRequiredDto used by the human-interactions view. */
export type InteractionDto = HumanActionRequiredDto

/** §6.2 propose_plan_change — single plan operation. */
export interface PlanChangeOperationDto {
  op: PlanChangeOp
  stepId: string
  target?: string
}

/** §6.2 propose_plan_change output / proposal listing item. */
export interface PlanChangeProposalDto {
  proposalId: string
  workflowId: string
  status: string
  summary: string
  operations: PlanChangeOperationDto[]
  revision: number
}

/**
 * Controller case transition entry — distinct from worker execution steps (§1.2:
 * `WorkflowTransitionNode` worker steps vs controller cases).
 *
 * NOTE: the Phase 0 doc defines no concrete DTO schema for controller history.
 * This is a minimal inferred shape — confirm the exact wire shape in Phase 6.
 */
export interface ControllerHistoryEntryDto {
  caseId: string
  fromStatus: string
  toStatus: string
  at: string
  note?: string
}

/** Controller case history for one workflow. Phase 6 shape TBD (see note above). */
export interface ControllerHistoryDto {
  workflowId: string
  revision: number
  entries: ControllerHistoryEntryDto[]
}

/**
 * View-model extension (NOT part of the Phase 0 DTO contract).
 * Lane assignment used by the workflow-detail lanes view; derived from mock
 * metadata until Phase 6 defines a real source.
 */
export type StepLane = 'human' | 'agent' | 'code'

/**
 * Factory success envelope (§2.3): every `/api/factory/**` success payload is
 * wrapped in `{ "data": ... }`. The client unwraps it before mapping.
 */
export interface FactoryDataEnvelope<T> {
  data: T
}

/** Error payload carried by the Factory error envelope (§2.3, §7). */
export interface FactoryErrorPayload {
  code: string
  message: string
  details?: Record<string, unknown>
}

/** Factory error envelope: `{ "error": { code, message, details } }`. */
export interface FactoryErrorEnvelope {
  error: FactoryErrorPayload
}

/**
 * One action the backend explicitly authorizes from the current state
 * (`GET /api/factory/workflows/{workflowId}/actions`).
 *
 * `expectedRevision` is the revision the executing command must be fenced on.
 * Older/alternate DTO shapes may use `id`/`kind` instead of `type` — both are
 * accepted so the mapping never fabricates an action.
 */
export interface AllowedActionDto {
  /** Stable action type used for gating (`reply`, `retry`, `cancel_attempt`, `continue_cost`, `stop_cost`). */
  type?: AllowedActionType | string
  /** Alternate id form (legacy/mock DTO shape). */
  id?: string
  label?: string
  kind?: string
  enabled?: boolean
  reason?: string
  interactionId?: string
  stepId?: string
  attemptId?: string
  caseId?: string
  questionEventId?: string
  expectedRevision?: number
}

/** Unwrapped payload of `GET /api/factory/workflows/{workflowId}/actions`. */
export interface WorkflowActionsResponseDto {
  workflowId?: string
  revision?: number
  allowedActions: AllowedActionDto[]
  blockers: WorkflowBlockerDto[]
}

/**
 * Unwrapped payload of `GET /api/factory/workstreams/{workstreamId}/projection`
 * (Phase 5). `workstreamRevision` is a stable ETag-like hash over the aggregated
 * state; `asOf` is the freshness anchor.
 */
export interface WorkstreamProjectionResponseDto {
  workstreamId?: string
  workstreamRevision?: number | string
  revision?: number | string
  asOf?: string
  workflows?: WorkflowSummaryDto[]
  items?: WorkflowSummaryDto[]
  [key: string]: unknown
}

/**
 * Standardized command acknowledgement (Phase 7 output contract). Returned by
 * retry / reply / plan-change decision commands; `allowedActions` may be present
 * as a best-effort refresh of the authoritative action set.
 */
export interface FactoryCommandAckDto {
  status?: string
  revision?: number
  reasonCode?: string | null
  interactionId?: string | null
  proposalId?: string | null
  allowedActions?: AllowedActionDto[]
  message?: string | null
  [key: string]: unknown
}
