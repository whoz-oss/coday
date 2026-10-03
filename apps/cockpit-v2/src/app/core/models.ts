export type SandboxStatus = 'working' | 'idle' | 'destroyed'
export type RunStatus = 'running' | 'succeeded' | 'failed' | 'queued'
export type Tone = 'violet' | 'cyan' | 'amber' | 'green' | 'blue' | 'red' | 'neutral'

/** Segment de la barre de phases (request · plan · code · build…) */
export interface PhaseSegment {
  key: string
  ratio: number // part de la durée totale (0–1)
  tone: Tone
  status: 'done' | 'running' | 'pending'
}

export interface RunSummary {
  id: string
  workflow: string
  status: RunStatus
  currentPhase?: string
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
 * One active sandbox card, DERIVED from a real active workflow snapshot.
 *
 * The container fleet itself has no backend API; the available truth is the
 * list of active workflows, so every real field below is mapped from a
 * `/api/factory/workflows?state=active` item. The legacy mock-only fields
 * (`roster`, `wave`, `archayCostUsd`) are kept optional so older fixtures keep
 * compiling, but nothing derives or displays them anymore.
 */
export interface Sandbox {
  name: string
  project: string
  branch?: string
  status: SandboxStatus
  /** Namespace attribution of the underlying workflow, when known. */
  namespace?: string
  /** Workflow type (e.g. `adw_simple_sdlc`), when known. */
  workflowType?: string
  /** Jira/issue ticket carried through the workflow relations, when known. */
  ticket?: string
  finalCostUsd?: number // sandboxes détruites (only set when a real teardown exists)
  run?: RunSummary
  /** @deprecated no real source: model roster of the former mocked fleet. */
  roster?: string
  /** @deprecated no real source: wave label of the former mocked fleet. */
  wave?: string
  /** @deprecated no real source: Archay orchestrator cost. */
  archayCostUsd?: number
}

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

/* ───── Session ───── */

export interface SessionStep {
  key: string
  tone: Tone
  status: 'done' | 'running' | 'pending'
  durationSec?: number
}

export interface TimelineBlock {
  label: string
  description?: string
  startSec: number
  endSec: number
  status: 'done' | 'running'
  ticksSec?: number[] // instants d'événements (petits traits)
  errorTicksSec?: number[]
}

export interface TimelineLane {
  id: string
  label: string
  subtitle: string // rôle ou modèle
  kind: 'human' | 'workspace' | 'agent'
  tone: Tone
  contextPct?: number
  request?: TimelineBlock // bloc placé dans la colonne « request »
  blocks: TimelineBlock[]
}

export type RunEventType = 'phase_start' | 'log' | 'agent_start' | 'thinking' | 'tool_call' | 'agent_message'

export interface RunEvent {
  time: string // HH:mm:ss
  type: RunEventType
  tool?: string // read, write, bash, ls…
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

export interface PhaseDetail {
  name: string
  status: RunStatus
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
  sections: { label: string; count?: number; body?: string }[]
}

export interface HumanInteraction {
  interactionId: string
  stepId: string
  interactionType: string
  status: string
  prompt?: string
  actions?: Array<{ id: string; label: string }>
  recipient?: string
  createdAt?: string
}

/* ───── Governed actions & blockers (backend authority) ───── */

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
  status: RunStatus
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
}
