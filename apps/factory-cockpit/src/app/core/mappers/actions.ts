/**
 * Extraction of governed actions, blockers, interactions and attempts
 * from backend payloads. Pure, defensive, no Angular.
 */

import {
  AgentAttempt,
  AllowedAction,
  AllowedActionType,
  BlockerCode,
  HumanInteraction,
  WorkflowBlocker,
} from '../models'
import { asObject, getString, getNumber, getBoolean, asList } from './utils'

// ---------------------------------------------------------------------------
// Real cost
// ---------------------------------------------------------------------------

/**
 * Additive real-cost block exposed by `factory-service` under
 * `metrics.realCost`. Every field is optional for robustness.
 */
export interface RealCost {
  cost?: number
  unknownCostCount?: number
  liveTokens?: number
  paused?: boolean
  active?: boolean
  runCostThreshold?: number | null
}

/**
 * Defensively extract the `realCost` block from a metrics payload.
 */
export function extractRealCost(metrics: unknown): RealCost | undefined {
  const metricsObj = asObject(metrics)
  if (!metricsObj) return undefined
  const source = asObject(metricsObj['realCost']) ?? metricsObj

  const cost = getNumber(source, 'cost')
  const unknownCostCount = getNumber(source, 'unknownCostCount')
  const liveTokens = getNumber(source, 'liveTokens')
  if (cost === undefined && unknownCostCount === undefined && liveTokens === undefined) return undefined

  const thresholdRaw = source['runCostThreshold']
  const runCostThreshold = thresholdRaw === null ? null : (getNumber(source, 'runCostThreshold') ?? null)

  return {
    cost,
    unknownCostCount,
    liveTokens,
    paused: getBoolean(source, 'paused'),
    active: getBoolean(source, 'active'),
    runCostThreshold,
  }
}

// ---------------------------------------------------------------------------
// Interactions
// ---------------------------------------------------------------------------

function asInteractionArray(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asObject(payload)
  if (!obj) return []
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}

function extractActions(value: unknown): Array<{ id: string; label: string }> | undefined {
  if (!Array.isArray(value)) return undefined
  const actions: Array<{ id: string; label: string }> = []
  value.forEach((entry, index) => {
    if (typeof entry === 'string' && entry.length > 0) {
      actions.push({ id: entry, label: entry })
      return
    }
    const obj = asObject(entry)
    if (!obj) return
    const id = getString(obj, 'id') ?? getString(obj, 'actionId') ?? String(index + 1)
    const label = getString(obj, 'label') ?? getString(obj, 'name') ?? getString(obj, 'title') ?? id
    actions.push({ id, label })
  })
  return actions.length > 0 ? actions : undefined
}

/**
 * Defensively extract the human interactions from a `getInteractions` payload.
 */
export function extractInteractions(payload: unknown): HumanInteraction[] {
  return asInteractionArray(payload).map((item, index) => {
    const obj = asObject(item) ?? {}
    const payloadObj = asObject(obj['payload']) ?? {}
    const interaction: HumanInteraction = {
      interactionId: getString(obj, 'interactionId') ?? getString(obj, 'id') ?? `interaction-${index + 1}`,
      stepId: getString(obj, 'stepId') ?? getString(payloadObj, 'stepId') ?? '',
      interactionType: getString(obj, 'interactionType') ?? getString(obj, 'type') ?? 'unknown',
      status: getString(obj, 'status') ?? 'unknown',
    }
    const workflowId = getString(obj, 'workflowId')
    if (workflowId) interaction.workflowId = workflowId
    const revision = getNumber(obj, 'revision')
    if (revision !== undefined) interaction.revision = revision
    const prompt = getString(payloadObj, 'prompt') ?? getString(payloadObj, 'question') ?? getString(obj, 'prompt')
    if (prompt) interaction.prompt = prompt
    const questionType =
      getString(obj, 'questionType') ?? getString(payloadObj, 'questionType') ?? getString(payloadObj, 'type')
    if (questionType) interaction.questionType = questionType
    const rawOptions = payloadObj['options'] ?? obj['options']
    if (Array.isArray(rawOptions)) interaction.options = rawOptions.filter((o): o is string => typeof o === 'string')
    const actions = extractActions(payloadObj['actions'] ?? obj['actions'])
    if (actions) interaction.actions = actions
    const recipient = getString(payloadObj, 'recipient') ?? getString(obj, 'recipient')
    if (recipient) interaction.recipient = recipient
    const recipientRole = getString(obj, 'recipientRole') ?? getString(payloadObj, 'recipientRole')
    if (recipientRole) interaction.recipientRole = recipientRole
    const namespaceId = getString(obj, 'namespaceId') ?? getString(payloadObj, 'namespaceId')
    if (namespaceId) interaction.namespaceId = namespaceId
    const createdAt = getString(obj, 'createdAt') ?? getString(payloadObj, 'createdAt')
    if (createdAt) interaction.createdAt = createdAt
    return interaction
  })
}

// ---------------------------------------------------------------------------
// Attempts
// ---------------------------------------------------------------------------

function asAttemptArray(payload: unknown): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asObject(payload)
  if (!obj) return []
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}

/**
 * Defensively extract the real agent execution attempts from a `getAttempts` payload.
 */
export function extractAttempts(payload: unknown): AgentAttempt[] {
  return asAttemptArray(payload).map((item, index) => {
    const obj = asObject(item) ?? {}
    const attempt: AgentAttempt = {
      attemptId: getString(obj, 'attemptId') ?? getString(obj, 'id') ?? `attempt-${index + 1}`,
      stepId: getString(obj, 'stepId') ?? '',
      attemptNumber: getNumber(obj, 'attemptNumber') ?? 0,
      agentName: getString(obj, 'agentName') ?? '',
      status: getString(obj, 'status') ?? 'unknown',
      caseId: getString(obj, 'caseId') ?? '',
    }
    const failureCode = getString(obj, 'failureCode')
    if (failureCode) attempt.failureCode = failureCode
    const resultEvidenceId = getString(obj, 'resultEvidenceId')
    if (resultEvidenceId) attempt.resultEvidenceId = resultEvidenceId
    const revision = getNumber(obj, 'revision')
    if (revision !== undefined) attempt.revision = revision
    const createdAt = getString(obj, 'createdAt')
    if (createdAt) attempt.createdAt = createdAt
    const startedAt = getString(obj, 'startedAt')
    if (startedAt) attempt.startedAt = startedAt
    const completedAt = getString(obj, 'completedAt')
    if (completedAt) attempt.completedAt = completedAt
    const activeDurationMs = getNumber(obj, 'activeDurationMs')
    if (activeDurationMs !== undefined) attempt.activeDurationMs = activeDurationMs
    const activeSince = getString(obj, 'activeSince')
    if (activeSince) attempt.activeSince = activeSince
    const waitingDurationMs = getNumber(obj, 'waitingDurationMs')
    if (waitingDurationMs !== undefined) attempt.waitingDurationMs = waitingDurationMs
    const waitingSince = getString(obj, 'waitingSince')
    if (waitingSince) attempt.waitingSince = waitingSince
    return attempt
  })
}

// ---------------------------------------------------------------------------
// Allowed actions & blockers
// ---------------------------------------------------------------------------

/**
 * Defensively extract `allowedActions` from a `getActions` payload.
 */
export function extractAllowedActions(payload: unknown): AllowedAction[] {
  const actions: AllowedAction[] = []
  for (const item of asList(payload, 'allowedActions')) {
    const obj = asObject(item)
    if (!obj) continue
    const type = getString(obj, 'type')
    if (!type) continue
    const action: AllowedAction = { type: type as AllowedActionType }
    const label = getString(obj, 'label')
    if (label) action.label = label
    const interactionId = getString(obj, 'interactionId')
    if (interactionId) action.interactionId = interactionId
    const stepId = getString(obj, 'stepId')
    if (stepId) action.stepId = stepId
    const attemptId = getString(obj, 'attemptId')
    if (attemptId) action.attemptId = attemptId
    const caseId = getString(obj, 'caseId')
    if (caseId) action.caseId = caseId
    const questionEventId = getString(obj, 'questionEventId')
    if (questionEventId) action.questionEventId = questionEventId
    const expectedRevision = getNumber(obj, 'expectedRevision')
    if (expectedRevision !== undefined) action.expectedRevision = expectedRevision
    actions.push(action)
  }
  return actions
}

/**
 * Defensively extract `blockers` from a `getActions` payload.
 */
export function extractBlockers(payload: unknown): WorkflowBlocker[] {
  const blockers: WorkflowBlocker[] = []
  for (const item of asList(payload, 'blockers')) {
    const obj = asObject(item)
    if (!obj) continue
    const code = getString(obj, 'code') ?? 'UNKNOWN_RUNTIME'
    const message = getString(obj, 'message')
    const label = getString(obj, 'label') ?? message ?? getString(obj, 'details') ?? code
    const blocker: WorkflowBlocker = { code: code as BlockerCode, label }
    const stepId = getString(obj, 'stepId')
    if (stepId) blocker.stepId = stepId
    if (message) blocker.message = message
    const details = getString(obj, 'details')
    if (details) blocker.details = details
    blockers.push(blocker)
  }
  return blockers
}
