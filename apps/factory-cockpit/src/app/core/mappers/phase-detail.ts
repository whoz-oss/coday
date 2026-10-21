/**
 * Phase detail construction: builds the detail panel content for the active
 * or a specific step. Eliminates the duplication between buildPhaseDetail
 * and buildPhaseDetailForStep by using an optional targetStepId parameter.
 */

import { AgentAttempt, HumanInteraction, PhaseDetail, PhaseSection, PhaseSectionItem, RunStatus } from '../models'
import { asObject, getString, formatClock } from './utils'
import { classifyActorKind, actorName, resolveStepState, stepDurationSec } from './step-classification'
import { mapWorkflowStateToRunStatus } from './timeline'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function pickCurrentAttempt(stepAttempts: AgentAttempt[]): AgentAttempt | undefined {
  if (stepAttempts.length === 0) return undefined
  return stepAttempts.reduce((latest, attempt) => (attempt.attemptNumber >= latest.attemptNumber ? attempt : latest))
}

function interactionToItems(interactions: HumanInteraction[]): PhaseSectionItem[] {
  return interactions.map((interaction) => {
    const item: PhaseSectionItem = { title: interaction.interactionType, status: interaction.status }
    if (interaction.prompt) item.subtitle = interaction.prompt
    if (interaction.actions) item.actions = interaction.actions
    return item
  })
}

function evidenceToItems(evidenceItems: unknown[]): PhaseSectionItem[] {
  return evidenceItems.map((entry, index) => {
    const obj = asObject(entry) ?? {}
    const facts = asObject(obj['facts']) ?? {}
    const kind = getString(obj, 'kind') ?? getString(obj, 'evidenceId') ?? `sortie-${index + 1}`
    const item: PhaseSectionItem = { title: kind }
    const outcome = getString(obj, 'outcome')
    if (outcome) item.status = outcome
    const message = getString(facts, 'message') ?? getString(facts, 'text') ?? getString(facts, 'summary') ?? outcome
    if (message) item.subtitle = message
    return item
  })
}

// ---------------------------------------------------------------------------
// Core builder
// ---------------------------------------------------------------------------

export interface LaunchRequestDetailInput {
  title: string
  createdAt: string
  namespaceId?: string
  workflowId: string
  workflowType?: string
  author?: string
  text?: string
}

/** Build the real run-creation request detail without borrowing step/attempt data. */
export function buildLaunchRequestDetail(input: LaunchRequestDetailInput): PhaseDetail {
  const identityItems: PhaseSectionItem[] = [
    { title: 'Type', subtitle: 'demande lancement' },
    { title: 'Run', subtitle: input.title },
    { title: 'Créée le', subtitle: input.createdAt },
    { title: 'Namespace', subtitle: input.namespaceId ?? 'non renseigné' },
    { title: 'Workflow', subtitle: input.workflowType ?? input.workflowId },
    { title: 'Auteur', subtitle: input.author ?? 'non renseigné' },
  ]
  return {
    name: input.title,
    status: 'succeeded',
    stepStatus: 'completed',
    durationSec: 0,
    owner: input.author ?? 'non renseigné',
    kind: 'demande lancement',
    attempt: '—',
    sections: [
      { label: 'Demande de lancement', items: identityItems },
      { label: 'Commentaire initial', body: input.text ?? 'Aucun commentaire initial' },
    ],
  }
}

/**
 * Build a {@link PhaseDetail} for a step.
 *
 * When `targetStepId` is provided, the detail is built for that specific step.
 * When omitted, the "active" step (running/waiting_human/ready or last) is used.
 * Falls back to the active step when the target is not found.
 */
export function buildPhaseDetail(
  steps: unknown[],
  fallbackStatus: RunStatus,
  interactions: HumanInteraction[] = [],
  attempts: AgentAttempt[] = [],
  evidenceItems: unknown[] = [],
  targetStepId?: string,
  targetAttemptId?: string
): PhaseDetail {
  // Resolve the target step
  let active: unknown
  if (targetStepId) {
    active = steps.find((step) => {
      const obj = asObject(step)
      return getString(obj, 'id') === targetStepId || getString(obj, 'key') === targetStepId
    })
  }
  // Fall back to the "active" step (running/waiting_human/ready) or the last one
  if (!active) {
    active =
      steps.find((step) => ['running', 'waiting_human', 'ready'].includes(resolveStepState(step))) ??
      steps[steps.length - 1]
  }

  const obj = asObject(active) ?? {}
  const responsibility = asObject(obj['responsibility'])
  const duration = stepDurationSec(obj) ?? 0
  const description = getString(obj, 'description')
  const stepId = getString(obj, 'id') ?? getString(obj, 'key')
  const owner = getString(responsibility, 'name') ?? actorName(obj)
  const kind = getString(responsibility, 'kind') ?? classifyActorKind(obj)

  const stepAttempts = stepId ? attempts.filter((attempt) => attempt.stepId === stepId) : []
  const currentAttempt = targetAttemptId
    ? stepAttempts.find((attempt) => attempt.attemptId === targetAttemptId)
    : pickCurrentAttempt(stepAttempts)

  const detail: PhaseDetail = {
    name: getString(obj, 'name') ?? getString(obj, 'id') ?? 'phase',
    status: getString(obj, 'status') ? mapWorkflowStateToRunStatus(getString(obj, 'status'), [obj]) : fallbackStatus,
    stepStatus: resolveStepState(obj),
    durationSec: Math.round(duration),
    owner,
    kind,
    attempt: '1/1',
    sections: [],
  }

  if (stepId) detail.stepId = stepId

  if (stepAttempts.length > 0 && currentAttempt) {
    detail.attempt = `${currentAttempt.attemptNumber}/${stepAttempts.length}`
    detail.currentAttemptNumber = currentAttempt.attemptNumber
    detail.totalAttempts = stepAttempts.length
    detail.attempts = stepAttempts
    detail.agentName = currentAttempt.agentName
    detail.attemptStatus = currentAttempt.status
    detail.caseId = currentAttempt.caseId
    if (currentAttempt.failureCode) detail.failureCode = currentAttempt.failureCode
  }

  // Gates
  const stepInteractions = stepId ? interactions.filter((interaction) => interaction.stepId === stepId) : interactions
  const gates: PhaseSection = {
    label: 'Gates',
    count: stepInteractions.length,
    items: interactionToItems(stepInteractions),
  }
  if (stepInteractions.length === 0) gates.body = "Aucune gate d'interaction pour cette phase."

  // Sorties
  const stepEvidence = evidenceItems.filter((entry) => {
    const evidenceObj = asObject(entry) ?? {}
    const evidenceStepId = getString(evidenceObj, 'stepId')
    const evidenceId = getString(evidenceObj, 'evidenceId') ?? getString(evidenceObj, 'id')
    const matchesStep = stepId !== undefined && evidenceStepId === stepId
    const matchesAttempt =
      currentAttempt?.resultEvidenceId !== undefined && evidenceId === currentAttempt.resultEvidenceId
    return matchesStep || matchesAttempt
  })
  const outputs: PhaseSection = { label: 'Sorties', count: stepEvidence.length, items: evidenceToItems(stepEvidence) }
  if (stepEvidence.length === 0) outputs.body = 'Aucune sortie enregistr\u00e9e pour cette phase.'

  // Configuration de l'agent
  const agentItems: PhaseSectionItem[] = []
  if (detail.agentName ?? owner) agentItems.push({ title: 'Agent', subtitle: detail.agentName ?? owner })
  if (owner) agentItems.push({ title: 'R\u00f4le', subtitle: `${owner} (${kind})` })
  if (detail.caseId) agentItems.push({ title: 'Case', subtitle: detail.caseId })
  if (detail.totalAttempts) agentItems.push({ title: 'Tentatives', subtitle: detail.attempt })
  const agentConfig: PhaseSection = { label: "Configuration de l'agent", items: agentItems }
  if (agentItems.length === 0) agentConfig.body = 'Information agent non disponible'

  detail.sections = [gates, outputs, agentConfig]
  if (description) detail.sections.push({ label: 'Description', body: description })
  detail.sections.push(
    { label: 'Prompts compil\u00e9s', notAvailable: true, body: 'Non disponible (n\u00e9cessite exposition backend)' },
    {
      label: 'Mod\u00e8le LLM r\u00e9solu',
      notAvailable: true,
      body: 'Non disponible (n\u00e9cessite exposition backend)',
    }
  )

  return detail
}

/**
 * Build a map from stepId to {@link PhaseDetail} for every step in the projection.
 * Used so the session-page can display the correct detail panel for any selected step.
 */
export function mapProjectionToPhaseByStepId(
  steps: unknown[],
  fallbackStatus: RunStatus,
  interactions: HumanInteraction[] = [],
  attempts: AgentAttempt[] = [],
  evidenceItems: unknown[] = []
): Map<string, PhaseDetail> {
  const result = new Map<string, PhaseDetail>()
  for (const step of steps) {
    const obj = asObject(step)
    const stepId = getString(obj, 'id') ?? getString(obj, 'key')
    if (!stepId) continue
    result.set(stepId, buildPhaseDetail(steps, fallbackStatus, interactions, attempts, evidenceItems, stepId))
  }
  return result
}

/** Build exact attempt details without falling back to another attempt. */
export function mapProjectionToPhaseByAttemptId(
  steps: unknown[],
  fallbackStatus: RunStatus,
  interactions: HumanInteraction[] = [],
  attempts: AgentAttempt[] = [],
  evidenceItems: unknown[] = []
): Map<string, PhaseDetail> {
  const result = new Map<string, PhaseDetail>()
  for (const attempt of attempts) {
    if (!attempt.attemptId || !attempt.stepId) continue
    result.set(
      attempt.attemptId,
      buildPhaseDetail(steps, fallbackStatus, interactions, attempts, evidenceItems, attempt.stepId, attempt.attemptId)
    )
  }
  return result
}

// ---------------------------------------------------------------------------
// Events (needed by workflow.ts)
// ---------------------------------------------------------------------------

import { RunEvent, RunEventType } from '../models'

function eventTypeFor(kind: string): RunEventType {
  const normalized = kind.toLowerCase()
  if (normalized.includes('tool')) return 'tool_call'
  if (normalized.includes('think')) return 'thinking'
  if (normalized.includes('human') || normalized.includes('decision') || normalized.includes('agent'))
    return 'agent_message'
  if (normalized.includes('phase')) return 'phase_start'
  return 'log'
}

const MAX_EVENT_TEXT = 2_000
const MAX_COMMAND = 2_048
const MAX_STREAM = 8_192
const MAX_TASKS = 20
const ANSI =
  /[\u001B\u009B][[\]()#;?]*(?:(?:(?:[a-zA-Z\d]*(?:;[-a-zA-Z\d/#&.:=?%@~_]+)*)?\u0007)|(?:(?:\d{1,4}(?:[;:]\d{0,4})*)?[\dA-PR-TZcf-nq-uy=><~]))/g

function clean(value: unknown, limit: number): string | undefined {
  if (typeof value !== 'string') return undefined
  return value
    .replace(ANSI, '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
    .slice(0, limit)
}

function streamFact(value: unknown): { excerpt: string; truncated: boolean; sha256?: string } | undefined {
  if (typeof value === 'string')
    return { excerpt: clean(value, MAX_STREAM) ?? '', truncated: value.length > MAX_STREAM }
  const obj = asObject(value)
  if (!obj) return undefined
  const source = typeof obj['excerpt'] === 'string' ? obj['excerpt'] : ''
  const excerpt = clean(source, MAX_STREAM) ?? ''
  const result: { excerpt: string; truncated: boolean; sha256?: string } = {
    excerpt,
    truncated: obj['truncated'] === true || source.length > MAX_STREAM,
  }
  const hash = clean(obj['sha256'], 128)
  if (hash) result.sha256 = hash
  return result
}

function failedNxTasks(...streams: Array<{ excerpt: string } | undefined>): string[] {
  const output = streams.map((stream) => stream?.excerpt ?? '').join('\n')
  const found = new Set<string>()
  const patterns = [/Running target \S+ for project ([A-Za-z0-9._-]+) failed/gi, /Failed tasks?:\s*([^\n]+)/gi]
  for (const pattern of patterns) {
    for (const match of output.matchAll(pattern)) {
      clean(match[1], 256)
        ?.split(/[,\s]+/)
        .filter(Boolean)
        .forEach((task) => found.add(task))
      if (found.size >= MAX_TASKS) break
    }
  }
  return [...found].slice(0, MAX_TASKS)
}

function codeDiagnostic(
  obj: Record<string, unknown>,
  facts: Record<string, unknown>
): RunEvent['codeDiagnostic'] | undefined {
  const kind = getString(obj, 'kind')
  const verification = clean(facts['verification'], 256)
  const hasCodeFacts =
    kind === 'code-verification' || (kind === 'session-step' && (verification || facts['executed'] !== undefined))
  if (!hasCodeFacts) return undefined
  const timedOut = facts['timedOut'] === true
  const executed = facts['executed']
  const exitCode =
    typeof facts['exitCode'] === 'number' && Number.isFinite(facts['exitCode']) ? facts['exitCode'] : undefined
  const outcome = getString(obj, 'outcome')
  const status = clean(facts['status'], 64)
  const state =
    status === 'running' || outcome === 'running'
      ? 'running'
      : executed === false
        ? 'refused'
        : timedOut
          ? 'timed_out'
          : exitCode === 0 && outcome !== 'fail' && outcome !== 'failed'
            ? 'passed'
            : 'failed'
  const stdout = streamFact(facts['stdout'])
  const stderr = streamFact(facts['stderr'])
  const technical: Record<string, string | number | boolean> = {}
  for (const key of ['cwd', 'observedHead', 'rootIdentity', 'timeoutMs']) {
    const value = facts[key]
    if (typeof value === 'string') technical[key] = clean(value, 512) ?? ''
    else if (typeof value === 'number' && Number.isFinite(value)) technical[key] = value
  }
  if (stdout?.sha256) technical['stdoutSha256'] = stdout.sha256
  if (stderr?.sha256) technical['stderrSha256'] = stderr.sha256
  return {
    verification,
    state,
    command: clean(facts['command'], MAX_COMMAND),
    code: clean(facts['code'], 256),
    message: clean(facts['message'], MAX_EVENT_TEXT),
    exitCode,
    stdout,
    stderr,
    failedTasks: failedNxTasks(stdout, stderr),
    technical,
  }
}

export function mapEvidenceToEvents(items: unknown[], steps: unknown[]): RunEvent[] {
  const events: RunEvent[] = []
  const verificationsById = new Map<string, Record<string, unknown>>()
  for (const item of items) {
    const obj = asObject(item)
    if (getString(obj, 'kind') !== 'code-verification') continue
    const id = getString(obj, 'evidenceId') ?? getString(obj, 'id')
    if (id) verificationsById.set(id, obj ?? {})
  }

  const consumedVerificationIds = new Set<string>()
  for (const item of items) {
    const obj = asObject(item) ?? {}
    const ownFacts = asObject(obj['facts']) ?? {}
    const kind = getString(obj, 'kind') ?? 'log'
    const linkedEvidenceId = kind === 'session-step' ? getString(ownFacts, 'evidenceId') : undefined
    const linkedVerification = linkedEvidenceId ? verificationsById.get(linkedEvidenceId) : undefined
    const linkedFacts = asObject(linkedVerification?.['facts'])
    if (linkedVerification && linkedFacts) consumedVerificationIds.add(linkedEvidenceId as string)
    if (kind === 'code-verification') {
      const evidenceId = getString(obj, 'evidenceId') ?? getString(obj, 'id')
      if (evidenceId && consumedVerificationIds.has(evidenceId)) continue
      const hasLinkedTerminal = items.some((candidate) => {
        const candidateObj = asObject(candidate)
        const candidateFacts = asObject(candidateObj?.['facts'])
        return (
          getString(candidateObj, 'kind') === 'session-step' && getString(candidateFacts, 'evidenceId') === evidenceId
        )
      })
      if (hasLinkedTerminal) continue
    }
    // The capability evidence owns the verdict; the session evidence owns the
    // richer, redacted diagnostics and timing. Merge only through the explicit
    // evidence id so retries and running records from the same step stay distinct.
    const facts = linkedFacts ? { ...linkedFacts, ...ownFacts } : ownFacts
    const diagnosticSource = linkedVerification ? { ...linkedVerification, ...obj, kind: 'code-verification' } : obj
    const diagnostic = codeDiagnostic(diagnosticSource, facts)
    const event: RunEvent = {
      time: formatClock(getString(obj, 'createdAt') ?? getString(facts, 'startedAt')),
      type: eventTypeFor(kind),
      text:
        clean(diagnostic?.message ?? facts['message'] ?? facts['text'] ?? obj['outcome'] ?? kind, MAX_EVENT_TEXT) ??
        kind,
    }
    if (diagnostic) event.codeDiagnostic = diagnostic
    const tool = getString(facts, 'tool')
    if (tool) event.tool = tool
    const durationMs =
      typeof facts['durationMs'] === 'number' && Number.isFinite(facts['durationMs'])
        ? (facts['durationMs'] as number)
        : undefined
    if (durationMs !== undefined) event.durationSec = durationMs / 1000
    events.push(event)
  }

  if (events.length === 0) {
    for (const step of steps) {
      const obj = asObject(step) ?? {}
      const startedAt = getString(obj, 'startedAt')
      if (!startedAt) continue
      events.push({
        time: formatClock(startedAt),
        type: 'phase_start',
        text: getString(obj, 'name') ?? getString(obj, 'id') ?? 'step',
      })
    }
  }
  return events
}

export function mapInteractionsToEvents(interactions: HumanInteraction[]): RunEvent[] {
  return interactions.map((interaction) => ({
    time: formatClock(interaction.createdAt),
    type: 'agent_message',
    text: `[Human Gate - ${interaction.interactionType}] ${interaction.prompt ?? interaction.status}`,
  }))
}

export function sumFacts(items: unknown[], key: string): number {
  let total = 0
  for (const item of items) {
    const facts = asObject(asObject(item)?.['facts'])
    const value = facts
      ? typeof facts[key] === 'number' && Number.isFinite(facts[key])
        ? (facts[key] as number)
        : undefined
      : undefined
    if (value !== undefined) total += value
  }
  return total
}

/**
 * Filter a list of {@link RunEvent} to only those produced by a specific
 * step and, optionally, a specific attempt.
 *
 * Events are correlated via the evidence items that were the source of
 * each event. The mapping uses two complementary signals:
 *  1. `evidenceStepId` — present on most evidence items (`stepId` field);
 *  2. `evidenceId` — matched against the attempt’s `resultEvidenceId`.
 *
 * When `attemptId` is provided the filter is attempt-scoped (only evidence
 * items whose `evidenceId` matches the attempt’s `resultEvidenceId` or
 * whose `stepId` + `attemptId` match). When only `stepId` is provided the
 * filter is step-scoped (all evidence for that step, all attempts).
 *
 * Evidence items that have no `stepId` are included in the global view only
 * (when `stepId` is absent). Interaction events are always step-scoped by
 * `interaction.stepId`.
 *
 * @param allEvents    Full event list as produced by {@link mapEvidenceToEvents} +
 *                     {@link mapInteractionsToEvents}.
 * @param evidenceItems Raw evidence items (pre-map, carry `stepId` / `evidenceId`).
 * @param interactions  Raw interactions (carry `stepId`).
 * @param attempts      Mapped attempts (carry `stepId`, `attemptId`, `resultEvidenceId`).
 * @param stepId        Step to filter on. When absent, the full list is returned.
 * @param attemptId     Attempt to narrow to. When absent, all attempts of the step
 *                      are included.
 */
export function filterEventsForSelection(
  allEvents: RunEvent[],
  evidenceItems: unknown[],
  interactions: HumanInteraction[],
  attempts: AgentAttempt[],
  stepId: string | undefined,
  attemptId: string | undefined
): RunEvent[] {
  if (!stepId) return allEvents

  // Collect evidence ids that belong to the requested step/attempt.
  const matchingEvidenceIds = new Set<string>()
  for (const item of evidenceItems) {
    const obj = asObject(item) ?? {}
    const evStepId = getString(obj, 'stepId')
    const evId = getString(obj, 'evidenceId') ?? getString(obj, 'id')
    if (!evId) continue

    if (attemptId) {
      // Narrow to the specific attempt: either the item is the result evidence
      // of that attempt, or the item has both matching stepId and attemptId.
      const attempt = attempts.find((a) => a.attemptId === attemptId)
      const isResultEvidence = attempt?.resultEvidenceId === evId
      const hasMatchingAttemptId = getString(obj, 'attemptId') === attemptId
      if (isResultEvidence || (evStepId === stepId && hasMatchingAttemptId)) {
        matchingEvidenceIds.add(evId)
      }
    } else {
      // Step-scoped: any evidence for this step.
      if (evStepId === stepId) matchingEvidenceIds.add(evId)
    }
  }

  // Re-map only the matching evidence items to events.
  const filteredEvidenceItems = evidenceItems.filter((item) => {
    const obj = asObject(item) ?? {}
    const evId = getString(obj, 'evidenceId') ?? getString(obj, 'id')
    return evId !== undefined && matchingEvidenceIds.has(evId)
  })
  const evidenceEvents = mapEvidenceToEvents(filteredEvidenceItems, [])

  // Filter interactions to the step (interactions are step-scoped, not attempt-scoped).
  const stepInteractions = interactions.filter((i) => i.stepId === stepId)
  const interactionEvents = mapInteractionsToEvents(stepInteractions)

  return [...evidenceEvents, ...interactionEvents]
}
