export type SandboxStatus = 'working' | 'idle' | 'destroyed'
export type RunStatus = 'running' | 'succeeded' | 'failed' | 'queued'
export type Tone = 'violet' | 'cyan' | 'amber' | 'green' | 'blue' | 'red' | 'neutral'

/** Segment de la barre de phases (request \u00b7 plan \u00b7 code \u00b7 build\u2026) */
export interface PhaseSegment {
  key: string
  /** Nom lisible de la step (name du backend quand il diffère de l'id, ou libellé synthétique). */
  label?: string
  ratio: number // part de la dur\u00e9e totale (0\u20131)
  tone: Tone
  status: 'done' | 'failed' | 'cancelled' | 'indeterminate' | 'running' | 'waiting_human' | 'pending'
}

export interface RunSummary {
  id: string
  workflow: string
  status: RunStatus
  currentPhase?: string
  /**
   * True when at least one step is in `waiting_human` state.
   * Kept separate from `status` (which stays `'running'`) so the global
   * sandbox badge is not affected — only the run detail chip changes.
   */
  waitingHuman?: boolean
  goal: string
  costUsd: number
  /**
   * Number of cost-incurring units whose price is unknown. When > 0 the
   * displayed `costUsd` is only a LOWER BOUND: it must never be shown as an
   * exact figure. Absent/0 means the cost is considered complete.
   */
  unknownCostCount?: number
  durationSec: number
  tokens: number
  phases: PhaseSegment[]
}

/**
 * One Factory run, DERIVED from a real workflow snapshot.
 *
 * The container fleet itself has no backend API; the available truth is the
 * list of workflows, so every real field below is mapped from a
 * `/api/factory/workflows` item.
 *
 * `id` (the workflow id) is the unique run identity: it is the ONLY key the UI
 * may use to target an action. `namespaceId` is the workstream attribution and
 * is carried strictly in projections/mappings.
 */
export interface FactoryRun {
  /** Unique run identity (workflow id). */
  id: string
  /** Workflow type (e.g. `adw_simple_sdlc`), when known. */
  workflowType?: string
  /** Lifecycle status of the run. */
  status: SandboxStatus
  /**
   * Workstream attribution of the underlying workflow, when known. Runs without
   * one are regrouped into an explicit `unassigned` workstream.
   */
  namespaceId?: string
  /** Human-readable title of the run. */
  title: string
  /** Project the run belongs to (namespace id when known). */
  project: string
  /** Jira/issue ticket carried through the workflow relations, when known. */
  ticket?: string
  branch?: string
  /**
   * ISO-8601 creation timestamp of the workflow: when the `WorkflowProjection`
   * node was first written to Neo4j, preserved across all subsequent updates.
   * Absent when the backend did not return the field (e.g. non-governed
   * declarative projections). An unparseable value is dropped at mapping time.
   */
  createdAt?: string
  /** Real cost of the run (USD). */
  costUsd: number
  /** Total run duration in seconds. */
  durationSec: number
  /** Total tokens consumed by the run. */
  tokens: number
  /** Phase-bar segments of the run. */
  phases: PhaseSegment[]
  /**
   * AgentOS case id of the workflow controller execution (snapshot
   * `controllerExecution.caseId`). This is the STARTING case created by the
   * Factory controller to orchestrate the entire workflow run \u2014 not a
   * question/attempt case. Absent when the backend exposes no controller case
   * (normal state when no agent step has executed yet).
   */
  controllerCaseId?: string
  finalCostUsd?: number // destroyed runs (only set when a real teardown exists)
  /** Full run summary (status, current phase, goal…). */
  run?: RunSummary
}

/**
 * Visible workstream: every run grouped by its `namespaceId`.
 *
 * Grouping is done STRICTLY on `namespaceId` (never on the human title), so two
 * namespaces sharing the same display name remain distinct groups (homonyms).
 */
export interface WorkstreamView {
  namespaceId: string
  title: string
  runs: FactoryRun[]
}

/** Fallback workstream id for runs whose `namespaceId` is unknown. */
export const UNASSIGNED_NAMESPACE_ID = 'unassigned'

/** @deprecated Use {@link FactoryRun}. Kept as an alias for compatibility. */
export type Sandbox = FactoryRun

export interface RecentTask {
  title: string
  log: string
  costUsd: number
}

export interface CostSummary {
  /** Number of active workflows (= number of derived sandbox cards). */
  active: number
  /** Sum of the real workflow costs (`RunSummary.costUsd`). */
  workflowsUsd: number
  /** Real total: currently equal to `workflowsUsd` (no other real source). */
  totalUsd: number
  /** @deprecated no real source: was a mocked Archay orchestrator cost. */
  archayUsd?: number
  /** @deprecated no real source: was a mocked destroyed-sandbox total. */
  destroyedUsd?: number
  /**
   * Sum of the {@link RunSummary.unknownCostCount} of the active runs. When
   * > 0 the aggregated `workflowsUsd`/`totalUsd` are lower bounds.
   */
  unknownCostCount?: number
}

/* \u2500\u2500\u2500\u2500\u2500 Session \u2500\u2500\u2500\u2500\u2500 */

export interface SessionStep {
  key: string
  tone: Tone
  status: 'done' | 'running' | 'waiting_human' | 'pending'
  durationSec?: number
}

export type TimelineStepStatus =
  | 'pending'
  | 'ready'
  | 'running'
  | 'waiting_human'
  | 'completed'
  | 'failed'
  | 'indeterminate'
  | 'cancelled'

export interface TimelineBlock {
  label: string
  stepId?: string
  description?: string
  startSec: number
  endSec: number
  status: TimelineStepStatus
  ticksSec?: number[] // instants d'\u00e9v\u00e9nements (petits traits)
  errorTicksSec?: number[]
}

export interface TimelineLane {
  id: string
  label: string
  subtitle: string // r\u00f4le ou mod\u00e8le
  kind: 'human' | 'workspace' | 'agent'
  tone: Tone
  contextPct?: number
  request?: TimelineBlock // bloc plac\u00e9 dans la colonne \u00ab request \u00bb
  blocks: TimelineBlock[]
}

export type RunEventType = 'phase_start' | 'log' | 'agent_start' | 'thinking' | 'tool_call' | 'agent_message'

export interface RunEvent {
  time: string // HH:mm:ss
  type: RunEventType
  tool?: string // read, write, bash, ls\u2026
  text: string
  durationSec?: number
}

/**
 * A single real agent execution attempt, mapped from the backend
 * `DurableAgentAttemptDto` exposed by
 * `GET /api/factory/workflows/:id/attempts`.
 */
export interface AgentAttempt {
  attemptId: string
  stepId: string
  attemptNumber: number
  agentName: string
  status: string
  caseId: string
  failureCode?: string
  resultEvidenceId?: string
  revision?: number
  createdAt?: string
  startedAt?: string
  completedAt?: string
}

/**
 * One concrete entry rendered inside a {@link PhaseSection} (a gate, a real
 * output/evidence item, an agent configuration line\u2026). Only real fields are
 * carried; a missing field is left absent rather than fabricated.
 */
export interface PhaseSectionItem {
  title: string
  subtitle?: string
  status?: string
  details?: string
  actions?: Array<{ id: string; label: string }>
}

/**
 * One accordion section of the phase detail panel.
 *
 * `count` is only meaningful when the underlying collection is actually known.
 * `notAvailable` marks a section whose backend data does not exist yet: the UI
 * must show an explicit "unavailable" message and never a misleading `count: 0`.
 */
export interface PhaseSection {
  label: string
  count?: number
  body?: string
  items?: PhaseSectionItem[]
  notAvailable?: boolean
}

export interface PhaseDetail {
  name: string
  stepId?: string
  status: RunStatus
  stepStatus: TimelineStepStatus
  durationSec: number
  owner: string
  kind: string
  attempt: string
  /** Highest `attemptNumber` observed for the active step (real attempts only). */
  currentAttemptNumber?: number
  /** Number of real attempts recorded for the active step. */
  totalAttempts?: number
  /** Real attempts recorded for the active step. */
  attempts?: AgentAttempt[]
  /** Agent name of the current attempt, when known. */
  agentName?: string
  /** Status of the current attempt (e.g. running, completed, failed). */
  attemptStatus?: string
  /** Case id of the current attempt, when known. */
  caseId?: string
  /** Failure code of the current attempt, when it failed. */
  failureCode?: string
  sections: PhaseSection[]
}

export type AgentQuestionType = 'FREE_TEXT' | 'SINGLE_CHOICE' | 'OPEN_CHOICE' | string

export interface AgentQuestion {
  questionEventId: string
  caseId: string
  attemptId: string
  stepId: string
  question: string
  questionType: AgentQuestionType
  options?: string[]
  answered: boolean
  case?: {
    id: string
    namespaceId: string
    workflowId: string
    stepId: string
  }
}

/**
 * Result of a successful supervisor case creation, returned by
 * {@link FactoryStore.openSupervisorCase}.
 */
export interface SupervisorCaseResult {
  /** The newly created AgentOS case id. */
  caseId: string
  /** The namespace id the case was created in. */
  namespaceId: string
  /**
   * The relative AgentOS UI URL (`/agentos/home?ns=...&case=...`).
   * The caller uses this to navigate an already-open window, avoiding
   * popup-blocker rejection (the window must be opened synchronously before
   * the async case-creation call).
   */
  agentOsUrl: string
}

export interface HumanInteraction {
  interactionId: string
  workflowId?: string
  stepId: string
  interactionType: string
  status: string
  revision?: number
  prompt?: string
  questionType?: AgentQuestionType
  options?: string[]
  actions?: Array<{ id: string; label: string }>
  recipient?: string
  recipientRole?: string
  namespaceId?: string
  createdAt?: string
}

/* \u2500\u2500\u2500\u2500\u2500 Governed actions & blockers (backend authority) \u2500\u2500\u2500\u2500\u2500 */

/** Stable `type` values of an {@link AllowedAction} (backend `WorkflowActionTypes`). */
export type AllowedActionType = 'reply' | 'retry' | 'cancel_attempt' | 'continue_cost' | 'stop_cost'

/**
 * One action the backend explicitly authorizes from the workflow's current
 * state. The cockpit may ONLY display/trigger actions present in
 * `allowedActions`; it never derives one itself and never fabricates the target
 * identity or the expected revision it must be fenced on.
 */
export interface AllowedAction {
  type: AllowedActionType
  label?: string
  interactionId?: string
  stepId?: string
  attemptId?: string
  caseId?: string
  questionEventId?: string
  /** Revision the executing command must carry (interaction/attempt/workflow). */
  expectedRevision?: number
}

/** Stable `code` values of a {@link WorkflowBlocker} (backend `WorkflowBlockerCodes`). */
export type BlockerCode =
  | 'WAITING_HUMAN_INTERACTION'
  | 'STEP_BLOCKED'
  | 'ATTEMPT_FAILED'
  | 'REAL_COST_PAUSED'
  | 'VERIFICATION_FAILED'
  | 'UNKNOWN_RUNTIME'

/** One active blocker preventing a workflow from progressing unattended. */
export interface WorkflowBlocker {
  code: BlockerCode
  /** Human-readable label (backend `message`, falling back to the code). */
  label: string
  stepId?: string
  message?: string
  details?: string
}

/** Normalized payload of `GET /api/factory/workflows/:id/actions`. */
export interface GetActionsResponse {
  allowedActions: AllowedAction[]
  blockers: WorkflowBlocker[]
}

export interface SessionDetail {
  id: string
  sandbox: string
  goal: string
  /**
   * AgentOS case id of the workflow controller execution (snapshot
   * `controllerExecution.caseId`). Same source as {@link Sandbox.controllerCaseId}.
   * Absent when the backend exposes no controller case.
   */
  controllerCaseId?: string
  status: RunStatus
  /** Authoritative execution state, kept separate from the coarse run status. */
  executionState: TimelineStepStatus
  /** True only while execution is actively progressing (never while waiting for a human). */
  activelyRunning: boolean
  startedAt: string // ISO
  workflow: string
  costUsd: number
  /**
   * Number of cost-incurring units whose price is unknown (see
   * {@link RunSummary.unknownCostCount}). When > 0 `costUsd` is a lower bound.
   */
  unknownCostCount?: number
  durationSec: number
  tokens: number
  tokensRead: number
  tokensWritten: number
  steps: SessionStep[]
  lanes: TimelineLane[]
  nowSec: number
  events: RunEvent[]
  phase: PhaseDetail
  /**
   * Human interactions (gates/approvals/checkpoints) attached to the workflow,
   * surfaced read-only. Absent when the backend exposes none (or when the
   * enrichment call failed and degraded gracefully).
   */
  interactions?: HumanInteraction[]
  /**
   * Real agent execution attempts attached to the workflow, surfaced read-only.
   * Absent when the backend exposes none (or when the enrichment failed).
   */
  attempts?: AgentAttempt[]
  /**
   * Attempt id the cockpit may cancel right now, resolved from real state
   * (a `cancel_attempt` allowed action, else a running attempt). Absent when no
   * active attempt is resolvable: the stop action is then unavailable and the
   * cockpit must never fabricate an id.
   */
  activeAttemptId?: string
  /**
   * Revision the cancel command must be fenced on for {@link activeAttemptId},
   * taken verbatim from the backend. Undefined when the backend exposes none.
   */
  activeAttemptRevision?: number
  /**
   * Actions the backend explicitly authorizes from the current state (see
   * `GET /api/factory/workflows/:id/actions`). The cockpit renders an action
   * button ONLY when it is present here. Absent/empty means nothing is allowed.
   */
  allowedActions?: AllowedAction[]
  /**
   * Active blockers that prevent the workflow from progressing unattended.
   * Empty when none are active or when the fetch degraded gracefully.
   */
  blockers?: WorkflowBlocker[]
  /** Active durable AgentOS queryUser questions projected for this workflow. */
  agentQuestions?: AgentQuestion[]
  /** Read failure of the question projection; case metadata enables a safe fallback link. */
  agentQuestionsError?: {
    code: string
    message: string
    caseId?: string
    namespaceId?: string
    workflowId?: string
    stepId?: string
  }
}
