/**
 * Factory Cockpit — phase (step) inspector panel.
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * Given a normalized projection v2 step, this component renders:
 *
 *   - a header: type badge, name, status badge, duration chip and flag chips;
 *   - the classified facts of the step (via {@link renderFactGroups});
 *   - the recorded workflow evidence attached to that step;
 *   - an optional right column enriched from external sources: the AgentOS case
 *     event stream (`/api/cases/:caseId/events`) and the Jira ticket content
 *     (`/api/factory/jira/:ticketId`).
 *
 * External enrichment is always best-effort: {@link loadPhaseEnrichment} never
 * throws, and every failure degrades into an explicit, escaped notice instead of
 * a silent console error or a broken panel.
 *
 * SECURITY: every dynamic value is escaped with {@link esc}. Jira content and
 * case events are external, mutable text; they are rendered as text, never HTML.
 */

import { esc, fmtDur, collectFlags, emptySuccess, EMPTY_SUCCESS_FLAG, renderFactGroups } from './facts.mjs'

/**
 * Max length accepted by the reply endpoint for the optional `text` field; kept
 * in sync with the server contract so the UI never sends a rejected payload.
 */
export const CHECKPOINT_COMMENT_MAX = 2000

/** Status badge class, aligned with the dockyard chip palette. */
const STATUS_CHIP = Object.freeze({
  pass: 'chip chip-success',
  fail: 'chip chip-fail',
  running: 'chip chip-running',
  blocked: 'chip chip-wave',
  pending: 'chip',
})

const STATUS_LABELS = Object.freeze({
  pass: 'réussie',
  fail: 'échouée',
  running: 'en cours',
  blocked: 'bloquée',
  pending: 'en attente',
})

function truncate(value, max) {
  const text = String(value ?? '')
  return text.length <= max ? text : `${text.slice(0, max)}…`
}

/** Concatenate the textual parts of a case `MessageEvent`. */
export function messageText(event) {
  return (event?.content ?? [])
    .filter((part) => part && typeof part.content === 'string')
    .map((part) => part.content)
    .join('')
}

/** First user message of a case event stream. */
export function firstUserMessage(events) {
  const event = (events ?? []).find((entry) => entry?.type === 'MessageEvent' && entry.actor?.role === 'USER')
  return event ? messageText(event) : null
}

/** Last agent message of a case event stream. */
export function lastAgentMessage(events) {
  const list = (events ?? []).filter((entry) => entry?.type === 'MessageEvent' && entry.actor?.role === 'AGENT')
  return list.length > 0 ? messageText(list[list.length - 1]) : null
}

/** One-line summary per case event type. Unknown types fall back to raw keys. */
export function eventSummary(event) {
  if (!event || typeof event !== 'object') return ''
  switch (event.type) {
    case 'MessageEvent':
      return `${event.actor?.displayName ?? event.actor?.role ?? ''} · ${truncate(messageText(event), 120)}`
    case 'ToolRequestEvent':
      return `${event.toolName}(${truncate(JSON.stringify(event.args ?? {}), 90)})`
    case 'ToolResponseEvent':
      return `${event.success === false ? '✗ ' : '✓ '}${event.toolName ?? ''}${
        event.durationMs != null ? ` · ${fmtDur(event.durationMs)}` : ''
      }`
    case 'AgentSelectedEvent':
      return event.agentName ?? ''
    case 'AgentRunningEvent':
    case 'AgentFinishedEvent':
      return [event.agentName, event.llmProvider, event.llmModel].filter(Boolean).join(' · ')
    case 'CaseStatusEvent':
      return event.status ?? ''
    case 'ErrorEvent':
    case 'WarnEvent':
      return truncate(event.message ?? '', 120)
    default: {
      const skip = new Set(['id', 'caseId', 'namespaceId', 'timestamp', 'type', 'metadata'])
      return Object.keys(event)
        .filter((key) => !skip.has(key))
        .join(', ')
    }
  }
}

/**
 * True when a normalized projection step belongs to the human lane.
 *
 * The Governed Projection v2 carries the lane both on `phaseKind` (normalized by
 * the Gantt model) and on `responsibility.kind`; either is authoritative.
 *
 * @param {any} step
 * @returns {boolean}
 */
export function isHumanStep(step) {
  if (!step || typeof step !== 'object') return false
  if (step.phaseKind === 'human' || step.lane === 'human') return true
  return step.responsibility?.kind === 'human'
}

/**
 * Find the OPEN human interaction attached to a step, from the loaded enrichment.
 *
 * Returns `null` when no interaction is waiting (already resolved, not loaded,
 * or belonging to another step) — which is exactly the signal the panel uses to
 * hide the decision buttons.
 *
 * @param {any} step
 * @param {{ interaction?: object|null }|null} [enrichment]
 * @returns {object|null}
 */
export function findWaitingInteraction(step, enrichment) {
  const interaction = enrichment?.interaction ?? null
  if (!interaction) return null
  if (interaction.status && interaction.status !== 'waiting') return null
  if (step?.id && interaction.stepId && interaction.stepId !== step.id) return null
  return interaction
}

/**
 * Resolve the human-checkpoint state of a step: whether a decision card applies
 * and whether the step is still awaiting an answer.
 *
 * The normalized step status folds `waiting_human` into the legible `blocked`
 * status, so the raw `projectionStatus` is honoured too; an open interaction is
 * itself proof of a pending decision.
 *
 * @param {any} step
 * @param {{ interaction?: object|null }|null} [enrichment]
 * @returns {{ relevant: boolean, waiting: boolean, interaction: object|null }}
 */
export function humanCheckpoint(step, enrichment) {
  const interaction = findWaitingInteraction(step, enrichment)
  return {
    relevant: isHumanStep(step),
    waiting: Boolean(interaction) || step?.status === 'blocked' || step?.projectionStatus === 'waiting_human',
    interaction,
  }
}

/**
 * Load the optional external enrichment for a step.
 *
 * Never rejects: a 404, a 501 (Jira not configured) or a network failure all
 * degrade into `notices` so the panel can explain what is missing.
 *
 * When a `workflowId` is supplied for a human/waiting step, the open human
 * interactions are fetched (`GET .../interactions`) and the one matching this
 * step is exposed as `interaction`, so the panel can offer a decision.
 *
 * @param {object|null|undefined} step
 * @param {{ apiClient?: any, signal?: AbortSignal, workflowId?: string|null, namespaceId?: string|null }} [options]
 * @returns {Promise<{ caseId: string|null, ticketId: string|null, caseEvents: Array<object>|null, ticket: object|null, notices: Array<object>, interaction: object|null }>}
 */
export async function loadPhaseEnrichment(step, options = {}) {
  const { apiClient = null, signal, workflowId = null, namespaceId = null } = options
  const facts = step?.facts ?? {}
  const caseId = facts.caseId != null ? String(facts.caseId) : null
  const ticketId = facts.ticketId != null ? String(facts.ticketId) : null
  const notices = []
  let caseEvents = null
  let ticket = null

  if (apiClient && typeof apiClient.get === 'function' && caseId) {
    try {
      const payload = await apiClient.get(`/api/cases/${encodeURIComponent(caseId)}/events`, { signal })
      caseEvents = Array.isArray(payload) ? payload : Array.isArray(payload?.items) ? payload.items : null
      if (!caseEvents)
        notices.push({ kind: 'case', label: 'AgentOS non disponible', message: 'Format de réponse inattendu.' })
    } catch (error) {
      notices.push({
        kind: 'case',
        label: 'AgentOS non disponible',
        message: String(error?.message ?? error),
      })
    }
  }

  if (apiClient && typeof apiClient.get === 'function' && ticketId) {
    try {
      ticket = await apiClient.get(`/api/factory/jira/${encodeURIComponent(ticketId)}`, { signal })
    } catch (error) {
      notices.push({
        kind: 'jira',
        label: 'Contenu du ticket non disponible',
        message: String(error?.message ?? error),
      })
    }
  }

  // A human/awaiting step may be suspended on an open interaction: fetch the
  // workflow interactions and keep the one waiting for THIS step, so the phase
  // panel can render the decision buttons with the authoritative revision.
  let interaction = null
  const mayAwaitHuman = isHumanStep(step) || step?.status === 'blocked' || step?.projectionStatus === 'waiting_human'
  if (apiClient && typeof apiClient.get === 'function' && workflowId && mayAwaitHuman) {
    try {
      const scope = namespaceId ? `?namespaceId=${encodeURIComponent(namespaceId)}` : ''
      const payload = await apiClient.get(
        `/api/factory/workflows/${encodeURIComponent(workflowId)}/interactions${scope}`,
        { signal },
      )
      const items = Array.isArray(payload) ? payload : Array.isArray(payload?.items) ? payload.items : []
      interaction = items.find((item) => item?.stepId === step?.id && item?.status === 'waiting') ?? null
    } catch (error) {
      notices.push({
        kind: 'interaction',
        label: 'Interactions humaines indisponibles',
        message: String(error?.message ?? error),
      })
    }
  }

  return { caseId, ticketId, caseEvents, ticket, notices, interaction }
}

/**
 * Render the human decision card grafted into the phase detail panel.
 *
 * When an OPEN interaction is present, the card offers an optional comment and
 * the Approve / Reject buttons carrying `data-checkpoint-action`. When the step
 * merely awaits an interaction that is not loaded yet, the card explains the
 * wait WITHOUT any button (never a decision without an authoritative revision).
 *
 * @param {any} step
 * @param {object|null} interaction
 * @param {{ submitting?: boolean, feedback?: { type: string, message: string }|null }|null} [state]
 * @returns {string} escaped markup
 */
function renderCheckpointCard(step, interaction, state = null) {
  const submitting = state?.submitting === true
  const feedback = state?.feedback ?? null
  const title = step?.name ?? step?.id ?? 'Checkpoint humain'
  const prompt = interaction?.prompt != null ? String(interaction.prompt) : ''
  const disabled = submitting ? ' disabled' : ''

  const fields = interaction
    ? '<textarea id="checkpoint-comment" name="checkpoint-comment" maxlength="' +
      CHECKPOINT_COMMENT_MAX +
      '" rows="2" placeholder="Commentaire optionnel (' +
      CHECKPOINT_COMMENT_MAX +
      ' caractères max)…" style="width:100%;margin-top:8px;background:var(--bg);border:1px solid var(--border-soft);' +
      'border-radius:6px;padding:8px 10px;color:var(--text);font-size:12px;resize:vertical"' +
      disabled +
      '></textarea>' +
      '<div class="checkpoint-actions" style="display:flex;gap:8px;flex-wrap:wrap;margin-top:10px">' +
      `<button type="button" class="btn btn-primary" data-checkpoint-action="approve"${disabled}>` +
      `${submitting ? 'Envoi…' : 'Approuver'}</button>` +
      `<button type="button" class="btn btn-danger" data-checkpoint-action="reject"${disabled}>Rejeter</button>` +
      '</div>'
    : feedback?.type === 'success'
      ? ''
      : '<span class="nil">En attente de l’ouverture de l’interaction…</span>'

  const feedbackHtml = feedback
    ? `<div class="checkpoint-feedback" data-checkpoint-feedback="${esc(feedback.type ?? 'info')}" ` +
      `style="margin-top:8px;font-size:11.5px;color:${feedback.type === 'error' ? 'var(--red)' : 'var(--green)'}">` +
      `${esc(feedback.message ?? '')}</div>`
    : ''

  return (
    '<div class="panel-col checkpoint-card" data-checkpoint="true" ' +
    'style="grid-column:1/-1;border:1px solid var(--amber);border-radius:8px;padding:12px;background:var(--panel-2)">' +
    '<h4 style="font-size:11px;text-transform:uppercase;color:var(--amber);margin:0 0 6px">Validation humaine</h4>' +
    `<div class="checkpoint-title" style="font-weight:600;margin-bottom:4px">${esc(title)}</div>` +
    (prompt
      ? `<div class="checkpoint-prompt" style="color:var(--dim);font-size:12px;line-height:1.6">${esc(prompt)}</div>`
      : '') +
    fields +
    feedbackHtml +
    '</div>'
  )
}

function renderNotice(notice) {
  return (
    '<div class="panel-notice" style="background:var(--panel-2);border:1px solid var(--border-soft);' +
    'border-radius:6px;padding:9px 12px;margin-top:8px;color:var(--warn,var(--amber));font-size:11px;line-height:1.6">' +
    `<strong>${esc(notice?.label ?? 'Indisponible')}</strong><br>${esc(notice?.message ?? '')}</div>`
  )
}

function renderEvidence(evidence, stepId) {
  const items = (Array.isArray(evidence) ? evidence : []).filter((entry) => !stepId || entry?.stepId === stepId)
  if (items.length === 0) return '<span class="nil">Aucune preuve enregistrée pour cette étape.</span>'
  return (
    '<div class="evlist">' +
    items
      .map((entry) => {
        const outcome = entry?.outcome ? ` · ${entry.outcome}` : ''
        const observed = entry?.observedAt ? ` · ${esc(entry.observedAt)}` : ''
        const facts = entry?.facts && typeof entry.facts === 'object' ? entry.facts : {}
        const factLine = Object.entries(facts)
          .map(([key, value]) => `${key}=${String(value)}`)
          .join(', ')
        return (
          '<div class="ev" style="border-bottom:1px solid var(--border-soft);padding:4px 0">' +
          `<span class="ty" style="color:var(--cyan)">${esc(entry?.kind ?? 'evidence')}</span>` +
          `<span class="sm"> · ${esc(outcome)}${observed}</span>` +
          (factLine ? `<div class="sm" style="color:var(--faint)">${esc(factLine)}</div>` : '') +
          '</div>'
        )
      })
      .join('') +
    '</div>'
  )
}

function renderCaseColumn(enrichment) {
  const events = enrichment?.caseEvents
  if (!Array.isArray(events)) {
    return (
      '<div class="panel-col">' +
      '<h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">Conversation ' +
      '<span class="src-tag">AgentOS, non enregistré</span></h4>' +
      '<span class="nil">Phase de code — aucun case AgentOS. Les commandes et leur verdict sont dans les faits.</span>' +
      '</div>'
    )
  }

  const brief = firstUserMessage(events)
  const reply = lastAgentMessage(events)
  const rows = events
    .map(
      (event) =>
        '<div class="ev" style="border-bottom:1px solid var(--border-soft);padding:3px 0">' +
        `<span class="t" style="color:var(--faint)">${esc(event?.timestamp ? String(event.timestamp) : '')}</span> ` +
        `<span class="ty" style="color:var(--cyan)">${esc(String(event?.type ?? '').replace(/Event$/, ''))}</span> ` +
        `<span class="sm">${esc(eventSummary(event))}</span></div>`
    )
    .join('')

  return (
    '<div class="panel-col">' +
    '<h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">Conversation ' +
    '<span class="src-tag">AgentOS, non enregistré</span></h4>' +
    (brief
      ? `<div class="gt" style="font-size:10px;color:var(--faint);margin-bottom:4px">Brief envoyé</div>` +
        `<div class="brief">${esc(brief)}</div>`
      : '') +
    (reply
      ? `<div class="gt" style="font-size:10px;color:var(--faint);margin:8px 0 4px">Réponse de l'agent</div>` +
        `<div class="brief reply">${esc(reply)}</div>`
      : '') +
    `<div class="gt" style="font-size:10px;color:var(--faint);margin:8px 0 4px">Événements (${events.length})</div>` +
    `<div class="evlist">${rows}</div>` +
    '</div>'
  )
}

function renderTicketColumn(enrichment) {
  const ticket = enrichment?.ticket
  return (
    '<div class="panel-col">' +
    '<h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">Contenu du ticket ' +
    '<span class="src-tag">Jira, récupéré maintenant</span></h4>' +
    '<div style="background:#1e1507;border:1px solid #4a3010;border-radius:6px;padding:9px 12px;' +
    'margin-bottom:12px;color:var(--amber);font-size:11px;line-height:1.6">' +
    '⚠️ Un ticket Jira est mutable : cette vue peut différer de ce qui a été transmis à l’analyste. ' +
    `Récupéré le ${esc(ticket?.fetchedAt ? String(ticket.fetchedAt) : 'maintenant')}.</div>` +
    '<pre style="background:var(--bg);border:1px solid var(--border-soft);border-radius:6px;padding:10px 12px;' +
    'white-space:pre-wrap;word-break:break-word;font-size:11.5px;line-height:1.6;color:var(--dim);' +
    'max-height:420px;overflow-y:auto">' +
    esc(ticket?.ticketContent ?? '') +
    '</pre></div>'
  )
}

/**
 * Render the step inspector panel.
 *
 * @param {{
 *   step?: object|null,
 *   workflow?: any,
 *   evidence?: Array<object>,
 *   enrichment?: { caseEvents?: Array<object>|null, ticket?: object|null, notices?: Array<object>, interaction?: object|null } | null,
 *   loading?: boolean,
 *   checkpoint?: { submitting?: boolean, feedback?: { type: string, message: string }|null } | null,
 * }} [options]
 * @returns {string} escaped markup
 */
export function renderPhasePanel(options = {}) {
  const { step = null, workflow = null, evidence = [], enrichment = null, loading = false, checkpoint = null } = options

  if (!step) {
    return (
      '<div class="panel" data-phase-panel="true"><p class="placeholder">' +
      'Sélectionner une étape dans la timeline.</p></div>'
    )
  }

  const status = step.status ?? 'pending'
  const stateLabel = STATUS_LABELS[status] ?? status
  const flags = collectFlags(step)
  if (emptySuccess(step)) flags.push(EMPTY_SUCCESS_FLAG)

  const facts = step.facts ?? {}
  const caseId = enrichment?.caseId ?? (facts.caseId != null ? String(facts.caseId) : null)
  const ticketId = enrichment?.ticketId ?? (facts.ticketId != null ? String(facts.ticketId) : null)

  const head =
    '<div class="panel-head" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:12px">' +
    `<span class="chip" data-step-kind="${esc(step.phaseKind)}">${esc(step.phaseKind)}</span>` +
    `<span class="pname" style="font-weight:600">${esc(step.name)}</span>` +
    `<span class="${STATUS_CHIP[status] ?? 'chip'}" data-step-status="${esc(status)}">${esc(stateLabel)}</span>` +
    `<span class="chip">⏱ ${esc(step.durationMs != null ? fmtDur(step.durationMs) : 'en cours')}</span>` +
    flags.map((flag) => `<span class="chip ${esc(flag.level)}">${esc(flag.icon)} ${esc(flag.label)}</span>`).join('') +
    '</div>'

  const factsColumn =
    '<div class="panel-col">' +
    '<h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">Faits ' +
    '<span class="src-tag">projection v2</span></h4>' +
    renderFactGroups(facts) +
    '</div>'

  const evidenceColumn =
    '<div class="panel-col">' +
    '<h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">Preuves ' +
    '<span class="src-tag">workflow</span></h4>' +
    renderEvidence(evidence, step.id) +
    '</div>'

  let narrativeColumn
  if (loading) {
    narrativeColumn =
      '<div class="panel-col"><h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">' +
      'Enrichissement</h4><span class="nil">Chargement…</span></div>'
  } else if (ticketId) {
    narrativeColumn = renderTicketColumn(enrichment)
  } else if (caseId) {
    narrativeColumn = renderCaseColumn(enrichment)
  } else {
    narrativeColumn =
      '<div class="panel-col"><h4 style="font-size:11px;text-transform:uppercase;color:var(--faint)">' +
      'Enrichissement</h4><span class="nil">Aucune source externe attachée à cette étape.</span></div>'
  }

  const notices = (enrichment?.notices ?? []).map(renderNotice).join('')

  // Human decision checkpoint: for a human step still awaiting an answer, and
  // kept visible after resolution while a success/error feedback is pending.
  const human = humanCheckpoint(step, enrichment)
  const checkpointCard =
    human.relevant && (human.waiting || checkpoint?.feedback)
      ? renderCheckpointCard(step, human.interaction, checkpoint)
      : ''

  const title = workflow?.projection?.title ?? workflow?.projection?.workflowType ?? ''
  return (
    `<div class="panel" data-phase-panel="true" data-step-id="${esc(step.id)}" data-step-name="${esc(step.name)}"` +
    (title ? ` data-workflow-title="${esc(title)}"` : '') +
    '>' +
    head +
    '<div class="panel-body" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px">' +
    checkpointCard +
    factsColumn +
    evidenceColumn +
    narrativeColumn +
    '</div>' +
    notices +
    '</div>'
  )
}

export default {
  renderPhasePanel,
  loadPhaseEnrichment,
  eventSummary,
  firstUserMessage,
  lastAgentMessage,
  messageText,
  isHumanStep,
  findWaitingInteraction,
  humanCheckpoint,
  CHECKPOINT_COMMENT_MAX,
}
