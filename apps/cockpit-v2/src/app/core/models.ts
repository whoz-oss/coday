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

export interface Sandbox {
  name: string
  project: string
  branch?: string
  roster: string
  status: SandboxStatus
  wave?: string
  archayCostUsd: number
  finalCostUsd?: number // sandboxes détruites
  run?: RunSummary
}

export interface RecentTask {
  title: string
  log: string
  costUsd: number
}

export interface CostSummary {
  active: number
  workflowsUsd: number
  archayUsd: number
  destroyedUsd: number
  totalUsd: number
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

export interface PhaseDetail {
  name: string
  status: RunStatus
  durationSec: number
  owner: string
  kind: string
  attempt: string
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
}
