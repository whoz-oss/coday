/**
 * Factory Cockpit — Forge console view (`#view-forge`).
 *
 * Vanilla ESM, zero dependencies, zero build step. Reconstructs the Angular
 * `factory-forge-runs` console (workstreams → workstream → epic → story) in the
 * vanilla cockpit, with the same four screens, the Forge run projections, the
 * G1 human gate and the live agent activity feed.
 *
 * Authority / data:
 *   - GET  /api/factory/forge/runs?namespaceId=…            (Epic/Story runs)
 *   - GET  /api/factory/workstreams?namespaceId=…           (workstream list)
 *   - GET  /api/forge/runs/:epic/stories/:story/executions   (story detail)
 *   - GET  /api/forge/runs/:epic/stories/:story/oracles
 *   - GET  /api/forge/runs/:epic/stories/:story/edits
 *   - POST /api/factory/forge/runs/:runId/gates/G1/decision?namespaceId=…
 *
 * SECURITY: the G1 decision body is built by {@link buildG1DecisionBody} and
 * NEVER carries `actorId`/`authorityId` — the server injects identity from
 * trusted headers, not from the browser.
 *
 * Lifecycle contract: `mount()` performs the initial load, wires the delegated
 * click handler and mounts the story sub-components (activity SSE, delivery).
 * The returned `unmount` is idempotent and leak-free: it tears down every
 * sub-component, aborts in-flight requests and removes the DOM listener.
 *
 * The module is import-safe in Node: no `window`/`document` access happens at
 * module evaluation.
 */

import { mountForgeActivity } from '../components/forge-activity.mjs'
import { mountDeliveryPanel } from '../components/delivery-panel.mjs'

/** The four console screens, from broadest to most detailed. */
export const SCREENS = Object.freeze(['streams', 'workstream', 'epic', 'story'])

/** The ten workflow steps, in order (mirrors the Angular `STEPS`). */
export const STEPS = Object.freeze([
  { key: 'discovery', name: 'Discovery', short: 'Discov.', weight: 1, level: 'Epic', kind: 'Phase 1 · future, grisée' },
  {
    key: 'grooming',
    name: 'Grooming',
    short: 'Grooming',
    weight: 1.25,
    level: 'US',
    kind: 'Phase 2 · agents + humain',
  },
  { key: 'g1', name: 'G1', short: 'G1', weight: 0.52, level: 'Epic', kind: 'Gate 1 · humain obligatoire' },
  {
    key: 'spec',
    name: 'Specification',
    short: 'Spec',
    weight: 1.15,
    level: 'US',
    kind: 'Phase 3 · agent analyste, read-only',
  },
  { key: 'g2', name: 'G2', short: 'G2', weight: 0.52, level: 'US', kind: 'Gate 2 · déterministe + reviewers' },
  { key: 'code', name: 'Code', short: 'Code', weight: 1.15, level: 'US', kind: 'Phase 4 · agent éditeur, writable' },
  { key: 'g3', name: 'G3', short: 'G3', weight: 0.52, level: 'US', kind: 'Gate 3 · oracles + revue adversariale' },
  { key: 'deploy', name: 'Deploy', short: 'Deploy', weight: 1, level: 'US', kind: 'Phase 5 · CI/CD COPS, grisée' },
  { key: 'g4', name: 'G4', short: 'G4', weight: 0.52, level: 'Epic', kind: 'Gate 4 · humain obligatoire' },
  { key: 'merge', name: 'Merge', short: 'Merge', weight: 1, level: 'US', kind: 'Phase 6 · GitHub' },
])

/** Steps carried by a User Story: Discovery is Epic-level and excluded. */
export const US_STEPS = Object.freeze(STEPS.filter((step) => step.key !== 'discovery'))

export const STEP_BY_KEY = Object.freeze(Object.fromEntries(STEPS.map((step) => [step.key, step])))

/** Run states and their Dockyard tones (colour + the word always written). */
export const TONES = Object.freeze({
  done: { bg: '#dcece2', ink: '#23543f', word: 'passé', chip: 'chip-success' },
  running: { bg: '#d9e8f5', ink: '#2c455d', word: 'en cours', chip: 'chip-running' },
  human: { bg: '#f4e8d2', ink: '#6f4a10', word: 'attente humaine', chip: 'chip-wave' },
  review: {
    bg: 'repeating-linear-gradient(45deg, #e3ecf5, #e3ecf5 5px, #cddfef 5px, #cddfef 10px)',
    ink: '#2c455d',
    word: 'en revue',
    chip: 'chip-running',
  },
  blocked: {
    bg: 'repeating-linear-gradient(45deg, #f4e8d2, #f4e8d2 5px, #e6d4b0 5px, #e6d4b0 10px)',
    ink: '#6f4a10',
    word: 'bloqué',
    chip: 'chip-wave',
  },
  failed: { bg: '#f5dcda', ink: '#7d2b29', word: 'échec', chip: 'chip-fail' },
  prior: { bg: '#f6ece0', ink: '#7a5a2a', word: 'diagnostic préexistant', chip: 'chip-wave' },
  stopped: {
    bg: 'repeating-linear-gradient(45deg, #e7e7ea, #e7e7ea 5px, #d5d5d8 5px, #d5d5d8 10px)',
    ink: '#5d5d60',
    word: 'interrompu',
    chip: 'chip',
  },
  pending: { bg: '#e7e7ea', ink: '#5d5d60', word: 'non démarré', chip: 'chip' },
  na: { bg: '#eaeaec', ink: '#5d5d60', word: 'non implémenté', chip: 'chip' },
})

export const G1_POLICY_VERSION = 'forge-g1-human-v1'

/** Escape a value for safe HTML text interpolation. */
export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Tone for a run state, defaulting to `pending`. */
export function toneOf(state) {
  return TONES[state] ?? TONES.pending
}

/** Read a step's state on a story (overrides win over declared states). */
export function stateOf(story, key, overrides) {
  return overrides?.[key] ?? story?.states?.[key] ?? 'pending'
}

/**
 * Real position of a story. A story can never sit on an unimplemented step:
 * fall back to the last actually observed step.
 */
export function headOf(story, overrides) {
  const declared = story?.head ?? US_STEPS[0].key
  if (stateOf(story, declared, overrides) !== 'na') return declared
  const seen = US_STEPS.map((step) => step.key).filter((key) => {
    const value = stateOf(story, key, overrides)
    return value !== 'na' && value !== 'pending'
  })
  return seen.length ? seen[seen.length - 1] : US_STEPS[0].key
}

/** Pluralize a count the French way. */
export function plural(count, one, many) {
  return `${count} ${count > 1 ? many : one}`
}

/** Derive the active screen from the navigation parameters. */
export function deriveScreen(params = {}) {
  if (params.story) return 'story'
  if (params.epic) return 'epic'
  if (params.ws) return 'workstream'
  return 'streams'
}

/**
 * Build the trusted G1 human decision body.
 *
 * The body MUST NOT carry `actorId`/`authorityId`: identity is injected on the
 * server from trusted headers. This is the single place constructing it.
 *
 * @param {{ evidenceSetHash: string, outcome?: string, reasonCode?: string, attempt?: number }} input
 * @returns {{ gate: string, attempt: number, policyVersion: string, evidenceSetHash: string, outcome: string, reasonCode: string }}
 */
export function buildG1DecisionBody(input = {}) {
  return {
    gate: 'G1',
    attempt: Number.isFinite(input.attempt) ? input.attempt : 1,
    policyVersion: G1_POLICY_VERSION,
    evidenceSetHash: input.evidenceSetHash,
    outcome: input.outcome ?? 'approved',
    reasonCode: input.reasonCode ?? 'intent_confirmed',
  }
}

/**
 * Map a raw `FactoryForgeRun` projection into the display `EpicRun` model.
 * Mirrors the Angular `mapForgeRunToEpicRun`.
 */
export function mapForgeRunToEpicRun(run) {
  const gates = Array.isArray(run?.gates) ? run.gates : []
  const g1 = gates.find((gate) => gate.gate === 'G1')
  const g3Summary = (run?.stories ?? []).map((story) => story?.oracleCampaigns?.at?.(-1)?.status ?? 'not_started')

  let closure
  if (run?.status === 'approved')
    closure = g3Summary.length > 0 && g3Summary.every((s) => s === 'passed') ? 'passed' : 'pending'
  else if (run?.status === 'waiting_human') closure = 'pending'
  else if (run?.status === 'blocked') closure = 'blocked'
  else closure = 'pending'

  const g1Status = g1?.status ?? 'not_started'
  const workItemId = run?.workItem?.id ?? run?.runId ?? ''

  return {
    key: workItemId,
    runId: run?.runId ?? null,
    title: `${workItemId} — ${run?.workflow ?? ''}`,
    workflow: run?.workflow ?? '',
    status: run?.status ?? 'not_started',
    closure,
    closureSummary: `G1: ${g1Status} · ${(run?.stories ?? []).length} US`,
    note: `Run ${String(run?.runId ?? '').slice(0, 8)} · démarré ${
      run?.startedAt ? new Date(run.startedAt).toLocaleDateString('fr-FR') : '?'
    }`,
    stories: (run?.stories ?? []).map((story) => mapStoryToStoryRun(story, g1Status)),
    gates,
    raw: run,
  }
}

/**
 * Map a raw Forge story into the display `StoryRun` model. Mirrors the Angular
 * `mapStoryToStoryRun`; the raw arrays are carried through for detail panels.
 */
export function mapStoryToStoryRun(story, epicG1Status) {
  const executions = Array.isArray(story?.executions) ? story.executions : []
  const edits = Array.isArray(story?.edits) ? story.edits : []
  const oracleCampaigns = Array.isArray(story?.oracleCampaigns) ? story.oracleCampaigns : []
  const latestExecution = executions.at(-1)
  const latestEdit = edits.at(-1)
  const latestCampaign = oracleCampaigns.at(-1)

  const g1State = epicG1Status === 'approved' ? 'done' : epicG1Status === 'waiting_human' ? 'human' : 'pending'

  const specState =
    latestExecution?.status === 'finished'
      ? 'done'
      : latestExecution?.status === 'failed'
        ? 'failed'
        : latestExecution
          ? 'running'
          : 'pending'
  const codeState =
    latestEdit?.status === 'finished'
      ? 'done'
      : latestEdit?.status === 'failed'
        ? 'failed'
        : latestEdit
          ? 'running'
          : 'pending'
  const g3State =
    latestCampaign?.status === 'passed'
      ? 'done'
      : latestCampaign?.status === 'blocked'
        ? 'blocked'
        : latestCampaign?.status === 'failed'
          ? 'failed'
          : latestCampaign
            ? 'running'
            : 'pending'
  const analysisValidation = latestExecution?.analysisValidation
  const g2State = g1State !== 'done' ? 'pending' : analysisValidation?.status === 'valid' ? 'done' : 'pending'
  const head =
    epicG1Status !== 'approved' ? 'g1' : latestCampaign ? 'g3' : latestEdit ? 'code' : latestExecution ? 'spec' : 'g1'

  const workItemId = story?.workItem?.id ?? story?.runId ?? ''
  return {
    key: workItemId,
    runId: story?.runId ?? null,
    title: workItemId,
    ticket: workItemId,
    pr: '—',
    status: story?.status ?? 'not_started',
    ordinal: story?.ordinal ?? 0,
    updatedAt:
      latestExecution?.observedAt ??
      latestEdit?.observedAt ??
      oracleCampaigns.at(-1)?.observedAt ??
      story?.updatedAt ??
      '?',
    head,
    states: {
      discovery: 'na',
      grooming: 'done',
      g1: g1State,
      spec: specState,
      g2: g2State,
      code: codeState,
      g3: g3State,
      deploy: 'na',
      g4: 'pending',
      merge: 'pending',
    },
    executions,
    edits,
    oracleCampaigns,
    workItem: story?.workItem ?? { id: workItemId, kind: 'story' },
  }
}

/* ------------------------------------------------------------------ render */

function renderRibbon({
  mode = 'detail',
  states = {},
  counts = {},
  current = null,
  selected = null,
  labels = true,
} = {}) {
  const cells = US_STEPS.map((step) => {
    const state = states[step.key] ?? 'pending'
    const tone = toneOf(state)
    const word = state === 'pending' ? '' : state === 'na' ? 'n/a' : tone.word
    const classes = ['ribbon-cell']
    if (selected === step.key) classes.push('selected')
    if (current === step.key) classes.push('current')
    return (
      `<button type="button" class="${classes.join(' ')}" data-step-key="${esc(step.key)}" ` +
      `data-step-state="${esc(state)}" style="flex:${step.weight};background:${tone.bg};color:${tone.ink}" ` +
      `title="${esc(step.name)} — ${esc(word || toneOf(state).word)}">` +
      (labels && mode !== 'distribution' ? `<span class="ribbon-name">${esc(step.name)}</span>` : '') +
      (mode === 'distribution'
        ? `<span class="ribbon-count">${esc(counts[step.key] ?? 0)}</span>`
        : `<span class="ribbon-word">${esc(word)}</span>`) +
      '</button>'
    )
  }).join('')
  return `<div class="forge-ribbon" data-forge-ribbon="true" data-ribbon-mode="${esc(mode)}">${cells}</div>`
}

function renderBreadcrumb(state) {
  const ws = state.workstream ? esc(state.workstream.name ?? state.workstream.slug) : 'Workstreams'
  const epic = state.epic ? esc(state.epic.key) : null
  const story = state.story ? esc(state.story.key) : null
  const crumbs = [
    `<button type="button" class="crumb" data-nav="streams">Workstreams</button>`,
    state.params.ws ? `<button type="button" class="crumb" data-nav="workstream">${ws}</button>` : '',
    epic ? `<button type="button" class="crumb" data-nav="epic">${epic}</button>` : '',
    story ? `<span class="crumb crumb--current">${story}</span>` : '',
  ].filter(Boolean)
  return (
    '<nav class="forge-breadcrumb" aria-label="Fil d’ariane">' +
    crumbs.join('<span class="crumb-sep" aria-hidden="true">/</span>') +
    '<button type="button" class="btn" data-forge-refresh="true">Rafraîchir</button>' +
    '</nav>'
  )
}

function renderFlags(flags) {
  return flags
    .map((flag) => `<span class="chip" style="color:${flag.ink};border-color:${flag.ink}">${esc(flag.label)}</span>`)
    .join('')
}

export function renderStreamsScreen(state) {
  const workstreams = state.workstreams ?? []
  const epicRuns = state.epicRuns ?? []
  const stories = epicRuns.flatMap((epic) => epic.stories)

  const flagsFor = () => {
    const blocked = stories.filter((story) =>
      US_STEPS.some((step) => ['blocked', 'failed'].includes(stateOf(story, step.key, state.overrides[story.key])))
    ).length
    const waiting = stories.filter((story) =>
      US_STEPS.some((step) => stateOf(story, step.key, state.overrides[story.key]) === 'human')
    ).length
    const done = stories.filter((story) => headOf(story, state.overrides[story.key]) === 'merge').length
    const flags = []
    if (blocked) flags.push({ label: plural(blocked, 'US bloquée', 'US bloquées'), ink: TONES.blocked.ink })
    if (waiting) flags.push({ label: `${waiting} attente humaine`, ink: TONES.human.ink })
    if (done) flags.push({ label: plural(done, 'US terminée', 'US terminées'), ink: TONES.done.ink })
    if (!flags.length) flags.push({ label: 'rien en attente', ink: TONES.done.ink })
    return flags
  }

  const cards = workstreams
    .map((ws) => {
      const counts = [
        plural(epicRuns.length, 'Epic', 'Epics'),
        `${stories.length} US`,
        plural(0, 'document', 'documents'),
      ].join(' · ')
      return (
        `<article class="workstream-card panel" data-ws-card="${esc(ws.slug)}">` +
        `<header class="workstream-card-head">` +
        `<h3 class="panel-title" style="margin:0">${esc(ws.name ?? ws.slug)}</h3>` +
        `<span class="chip">${esc(ws.status ?? '')}</span></header>` +
        `<div class="workstream-card-meta"><code class="cockpit-id">ws/${esc(ws.slug)}</code>` +
        `<span class="workstream-card-counts">${esc(counts)}</span></div>` +
        `<div class="workstream-card-flags">${renderFlags(flagsFor())}</div>` +
        `<div class="workstream-card-actions">` +
        `<button type="button" class="btn btn-primary" data-ws="${esc(ws.slug)}">Ouvrir</button></div>` +
        '</article>'
      )
    })
    .join('')

  const body = workstreams.length ? cards : '<p class="placeholder">Aucun workstream.</p>'
  return (
    `<section class="forge-screen screen--streams" data-forge-screen="streams">` +
    '<header class="screen-head"><h2 class="panel-title">Workstreams</h2>' +
    '<button type="button" class="btn" data-new-workstream="true">Nouveau workstream</button></header>' +
    `<div class="workstream-grid">${body}</div></section>`
  )
}

export function renderWorkstreamScreen(state) {
  const ws = state.workstream
  if (!ws) return '<p class="placeholder" data-forge-empty="workstream">Workstream introuvable.</p>'
  const epicRuns = state.epicRuns ?? []

  if (state.tab === 'docs') {
    return (
      `<section class="forge-screen screen--workstream" data-forge-screen="workstream">` +
      renderWorkstreamHeader(state, ws) +
      '<p class="placeholder" data-docs-placeholder="true">Aucun document indexé pour ce workstream.</p></section>'
    )
  }

  const rows = epicRuns
    .map((epic) => {
      const tally = new Map()
      for (const story of epic.stories) {
        const head = headOf(story, state.overrides[story.key])
        tally.set(head, (tally.get(head) ?? 0) + 1)
      }
      const proven = epic.stories.filter((story) => stateOf(story, 'g3', state.overrides[story.key]) === 'done').length
      const counts = Object.fromEntries(tally)
      return (
        `<article class="epic-row panel" data-epic-row="${esc(epic.key)}">` +
        `<header class="epic-row-head"><button type="button" class="epic-identity" data-epic="${esc(epic.key)}">` +
        `<strong>${esc(epic.key)}</strong> <span>${esc(epic.title)}</span></button>` +
        `<span class="chip">${esc(epic.closure)}</span>` +
        `<span class="chip">${esc(`${proven}/${epic.stories.length} G3 passées`)}</span></header>` +
        `<div class="epic-row-ribbon">${renderRibbon({ mode: 'distribution', counts })}</div>` +
        '</article>'
      )
    })
    .join('')

  const body = epicRuns.length ? rows : '<p class="placeholder">Aucune Epic pour ce workstream.</p>'
  return (
    `<section class="forge-screen screen--workstream" data-forge-screen="workstream">` +
    renderWorkstreamHeader(state, ws) +
    `<div class="epic-list" data-epic-count="${epicRuns.length}">${body}</div></section>`
  )
}

function renderWorkstreamHeader(state, ws) {
  const tabs =
    '<div class="forge-tabs">' +
    `<button type="button" class="${state.tab === 'epics' ? 'btn btn-primary' : 'btn'}" data-tab="epics">Epics</button>` +
    `<button type="button" class="${state.tab === 'docs' ? 'btn btn-primary' : 'btn'}" data-tab="docs">Documents</button>` +
    '</div>'
  return (
    `<header class="screen-head" data-workstream="${esc(ws.slug)}">` +
    `<h2 class="panel-title">${esc(ws.name ?? ws.slug)}</h2>` +
    `<span class="chip">${esc(ws.status ?? '')}</span>` +
    `<code class="cockpit-id">ws/${esc(ws.slug)}</code>${tabs}</header>`
  )
}

function renderG1Panel(state) {
  const g1 = state.g1
  if (!g1 || !g1.waitingHuman) return ''
  const error = state.g1Error ? `<p class="g1-error" role="alert">${esc(state.g1Error)}</p>` : ''
  return (
    '<div class="plate plate--g1 panel" data-g1-panel="true">' +
    '<div class="g1-head"><span class="chip chip-wave">Gate 1 · Attente approbation</span></div>' +
    '<p class="g1-lead">Le run est en attente d’une décision humaine sur l’intention. ' +
    'Approuvez pour démarrer l’analyse.</p>' +
    `<div class="actions"><button type="button" class="btn btn-primary" data-g1-approve="true"${
      state.g1Approving ? ' disabled' : ''
    }>${state.g1Approving ? 'Approbation…' : '✅ Approuver l’intention'}</button></div>` +
    (g1.evidenceSetHash
      ? `<code class="cockpit-id" data-g1-evidence="${esc(g1.evidenceSetHash)}">${esc(g1.evidenceSetHash)}</code>`
      : '') +
    error +
    '</div>'
  )
}

export function renderEpicScreen(state) {
  const epic = state.epic
  if (!epic) return '<p class="placeholder" data-forge-empty="epic">Epic introuvable.</p>'
  const sourceStories = epic.stories ?? []

  const rows = sourceStories
    .map((story) => {
      const states = Object.fromEntries(
        US_STEPS.map((step) => [step.key, stateOf(story, step.key, state.overrides[story.key])])
      )
      const head = headOf(story, state.overrides[story.key])
      const cells = US_STEPS.map((step) => {
        const st = states[step.key]
        const tone = toneOf(st)
        const classes = ['ribbon-cell']
        if (head === step.key) classes.push('current')
        return (
          `<button type="button" class="${classes.join(' ')}" data-story-cell="${esc(story.key)}" ` +
          `data-step-key="${esc(step.key)}" data-step-state="${esc(st)}" ` +
          `style="flex:${step.weight};background:${tone.bg};color:${tone.ink}" title="${esc(step.name)} — ${esc(tone.word)}">` +
          `<span class="ribbon-word">${esc(st === 'pending' ? '' : st === 'na' ? 'n/a' : tone.word)}</span></button>`
        )
      }).join('')
      return (
        `<div class="us-row" data-us-row="${esc(story.key)}">` +
        `<button type="button" class="us-identity" data-story="${esc(story.key)}">` +
        `<span class="us-key">${esc(story.key)}</span><span class="us-title">${esc(story.title)}</span></button>` +
        `<div class="us-cells" data-forge-ribbon="true" data-ribbon-mode="row">${cells}</div></div>`
      )
    })
    .join('')

  const grid = sourceStories.length ? rows : '<p class="placeholder">Aucune US dans cette Epic.</p>'
  return (
    `<section class="forge-screen screen--epic" data-forge-screen="epic">` +
    '<header class="screen-head">' +
    `<h2 class="panel-title">${esc(epic.key)} — ${esc(epic.title)}</h2>` +
    `<span class="chip">${esc(epic.closure)}</span></header>` +
    renderG1Panel(state) +
    `<div class="us-grid" data-us-count="${sourceStories.length}">${grid}</div>` +
    `<p class="screen-note">${esc(epic.note)}</p></section>`
  )
}

function renderStoryDetail(state) {
  const stepKey = state.stepKey ?? state.story?.head ?? 'grooming'
  const meta = STEP_BY_KEY[stepKey]
  if (!meta) return ''
  const stepState = stateOf(state.story, stepKey, state.overrides[state.story?.key])
  const tone = toneOf(stepState)
  return (
    `<div class="plate detail-panel panel" data-story-detail="${esc(stepKey)}">` +
    `<div class="detail-head"><span class="kicker">${esc(meta.kind)}</span>` +
    `<h3>${esc(meta.name)}</h3>` +
    `<span class="chip ${esc(tone.chip)}" data-step-state="${esc(stepState)}">${esc(tone.word)}</span>` +
    `<span class="chip">niveau ${esc(meta.level)}</span></div></div>`
  )
}

function renderStoryCollections(state) {
  const story = state.story
  if (!story) return ''
  const executions = Array.isArray(story.executions) ? story.executions : []
  const edits = Array.isArray(story.edits) ? story.edits : []
  const campaigns = Array.isArray(story.oracleCampaigns) ? story.oracleCampaigns : []

  const executionsHtml = executions.length
    ? `<ul class="records" data-story-executions="${executions.length}">${executions
        .map(
          (execution) =>
            `<li><span class="chip">${esc(execution.status)}</span> <code>${esc(execution.executionId)}</code> ` +
            `<span>${esc(execution.agentName ?? execution.role ?? '')}</span></li>`
        )
        .join('')}</ul>`
    : '<p class="placeholder">Aucune exécution enregistrée.</p>'

  const editsHtml = edits.length
    ? `<ul class="records" data-story-edits="${edits.length}">${edits
        .map(
          (edit) =>
            `<li><span class="chip">${esc(edit.status)}</span> <code>${esc(edit.editId)}</code> ` +
            `<span>${esc((edit.filesModified ?? []).length)} fichier(s) modifié(s)</span></li>`
        )
        .join('')}</ul>`
    : '<p class="placeholder">Aucune édition enregistrée.</p>'

  const oraclesHtml = campaigns.length
    ? `<ul class="records" data-story-oracles="${campaigns.length}">${campaigns
        .map(
          (campaign) =>
            `<li><span class="chip">${esc(campaign.status)}</span> <code>${esc(campaign.campaignId)}</code> ` +
            `<span>${esc((campaign.results ?? []).length)} oracle(s)</span></li>`
        )
        .join('')}</ul>`
    : '<p class="placeholder">Aucune campagne d’oracles enregistrée.</p>'

  return (
    '<div class="story-detail-grid">' +
    '<section class="panel" data-story-panel="executions"><h4>Exécutions</h4>' +
    executionsHtml +
    '</section>' +
    '<section class="panel" data-story-panel="edits"><h4>Éditions</h4>' +
    editsHtml +
    '</section>' +
    '<section class="panel" data-story-panel="oracles"><h4>Oracles</h4>' +
    oraclesHtml +
    '</section></div>'
  )
}

export function renderStoryScreen(state) {
  const story = state.story
  if (!story) return '<p class="placeholder" data-forge-empty="story">US introuvable.</p>'
  const states = Object.fromEntries(
    US_STEPS.map((step) => [step.key, stateOf(story, step.key, state.overrides[story.key])])
  )
  const head = headOf(story, state.overrides[story.key])
  const selected = state.stepKey ?? head

  const activityHost = state.storyCaseId
    ? `<div class="forge-activity-host" data-forge-activity-host="true" data-case-id="${esc(state.storyCaseId)}"></div>`
    : ''
  const deliveryHost = state.deliveryWorkflowId
    ? `<div class="forge-delivery-host" data-forge-delivery-host="true" data-workflow-id="${esc(
        state.deliveryWorkflowId
      )}"></div>`
    : ''

  return (
    `<section class="forge-screen screen--story" data-forge-screen="story" data-story-key="${esc(story.key)}">` +
    '<header class="screen-head">' +
    `<h2 class="panel-title">${esc(story.key)}</h2>` +
    `<span class="chip">${esc(story.status)}</span>` +
    `<code class="cockpit-id">${esc(story.ticket)}</code></header>` +
    `<div class="plate blueprint panel">${renderRibbon({ mode: 'detail', states, current: head, selected })}</div>` +
    renderStoryDetail(state) +
    renderStoryCollections(state) +
    activityHost +
    deliveryHost +
    '</section>'
  )
}

/** Render the whole console for a serialized controller state. */
export function renderForgeCockpit(state) {
  let body
  switch (state.screen) {
    case 'workstream':
      body = renderWorkstreamScreen(state)
      break
    case 'epic':
      body = renderEpicScreen(state)
      break
    case 'story':
      body = renderStoryScreen(state)
      break
    default:
      body = renderStreamsScreen(state)
  }
  const status = state.loading ? '<span class="chip chip-running">chargement</span>' : ''
  const error = state.error ? `<p class="error" data-forge-error="true">${esc(state.error)}</p>` : ''
  return (
    `<div class="forge-cockpit" data-forge-cockpit="true" data-screen="${esc(state.screen)}">` +
    renderBreadcrumb(state) +
    status +
    error +
    body +
    '</div>'
  )
}

/* ------------------------------------------------------------- controller */

const DEFAULT_REFRESH_DEBOUNCE_MS = 150

/**
 * Stateful, DOM-free Forge console controller. Owns the four-screen state
 * machine, the Forge run projection and the G1 decision flow so the view and
 * the offline test suite share one implementation.
 */
export class ForgeCockpitController {
  constructor(options = {}) {
    this.apiClient = options.apiClient ?? options.api ?? null
    this.namespaceId = options.namespaceId ?? null
    this.deliveryWorkflowId = options.deliveryWorkflowId ?? null
    this.onChange = typeof options.onChange === 'function' ? options.onChange : () => {}
    this.onError = typeof options.onError === 'function' ? options.onError : () => {}
    this.abortController = typeof AbortController === 'function' ? new AbortController() : null
    this.disposed = false

    this.state = {
      screen: deriveScreen(options.params ?? {}),
      params: {
        ws: options.params?.ws ?? null,
        epic: options.params?.epic ?? null,
        story: options.params?.story ?? null,
      },
      tab: options.tab === 'docs' ? 'docs' : 'epics',
      stepKey: options.stepKey ?? null,
      runs: [],
      workstreams: [],
      loading: false,
      error: null,
      overrides: {},
      g1Approving: false,
      g1Error: null,
    }
  }

  emitChange() {
    if (!this.disposed) this.onChange(this.getState())
  }

  listRunsPath() {
    return `/api/factory/forge/runs?namespaceId=${encodeURIComponent(this.namespaceId ?? '')}`
  }

  listWorkstreamsPath() {
    return `/api/factory/workstreams?namespaceId=${encodeURIComponent(this.namespaceId ?? '')}`
  }

  storyPath(section) {
    const epic = this.state.params.epic ?? ''
    const story = this.state.params.story ?? ''
    return (
      `/api/forge/runs/${encodeURIComponent(epic)}/stories/${encodeURIComponent(story)}/${section}` +
      `?namespaceId=${encodeURIComponent(this.namespaceId ?? '')}`
    )
  }

  g1DecisionPath(runId) {
    return (
      `/api/factory/forge/runs/${encodeURIComponent(runId)}/gates/G1/decision` +
      `?namespaceId=${encodeURIComponent(this.namespaceId ?? '')}`
    )
  }

  /** Mapped EpicRun list, sorted with active/waiting runs first then completed. */
  epicRuns() {
    const mapped = (this.state.runs ?? []).map((run) => mapForgeRunToEpicRun(run))
    const priority = (epic) => {
      if (epic.status === 'waiting_human') return 0
      if (epic.status === 'approved') return 3
      const started = epic.stories.some((story) =>
        Object.values(story.states).some((v) => v && v !== 'pending' && v !== 'na')
      )
      return started ? 1 : 2
    }
    return [...mapped].sort((a, b) => priority(a) - priority(b))
  }

  currentEpic() {
    const runs = this.epicRuns()
    const key = this.state.params.epic
    if (!key) return runs[0] ?? null
    return runs.find((epic) => epic.key === key) ?? runs[0] ?? null
  }

  currentForgeRun() {
    const key = this.state.params.epic
    const runs = this.state.runs ?? []
    return runs.find((run) => (run?.workItem?.id ?? run?.runId) === key) ?? runs[0] ?? null
  }

  currentForgeStory() {
    const run = this.currentForgeRun()
    if (!run) return null
    const stories = Array.isArray(run.stories) ? run.stories : []
    const key = this.state.params.story
    if (!key) return stories[0] ?? null
    return stories.find((story) => (story?.workItem?.id ?? story?.runId) === key) ?? stories[0] ?? null
  }

  currentStory() {
    const epic = this.currentEpic()
    if (!epic) return null
    const key = this.state.params.story
    if (!key) return epic.stories[0] ?? null
    return epic.stories.find((story) => story.key === key) ?? epic.stories[0] ?? null
  }

  currentWorkstream() {
    const slug = this.state.params.ws
    const list = this.state.workstreams ?? []
    if (!slug) return list[0] ?? null
    return list.find((ws) => ws.slug === slug) ?? list[0] ?? null
  }

  /** Latest execution caseId of the selected story, if any. */
  resolveStoryCaseId() {
    const story = this.currentForgeStory()
    const executions = Array.isArray(story?.executions) ? story.executions : []
    for (let index = executions.length - 1; index >= 0; index--) {
      const caseId = executions[index]?.caseId
      if (typeof caseId === 'string' && caseId) return caseId
    }
    return null
  }

  g1Gate() {
    const run = this.currentForgeRun()
    const gates = Array.isArray(run?.gates) ? run.gates : []
    return gates.find((gate) => gate.gate === 'G1') ?? null
  }

  /** Serialized state for rendering and assertions. */
  getState() {
    const epicRuns = this.epicRuns()
    const epic = this.currentEpic()
    const story = this.currentStory()
    const workstream = this.currentWorkstream()
    const g1 = this.g1Gate()
    return {
      screen: this.state.screen,
      params: { ...this.state.params },
      tab: this.state.tab,
      stepKey: this.state.stepKey,
      namespaceId: this.namespaceId,
      loading: this.state.loading,
      error: this.state.error,
      runs: this.state.runs,
      workstreams: this.state.workstreams,
      epicRuns,
      workstream,
      epic,
      story,
      overrides: this.state.overrides,
      storyCaseId: this.resolveStoryCaseId(),
      deliveryWorkflowId: this.deliveryWorkflowId ?? epic?.runId ?? null,
      g1: g1
        ? {
            runId: g1.runId ?? this.currentForgeRun()?.runId ?? null,
            status: g1.status,
            waitingHuman: g1.status === 'waiting_human',
            evidenceSetHash: g1.evidenceSetHash ?? null,
          }
        : null,
      g1Approving: this.state.g1Approving,
      g1Error: this.state.g1Error,
      storyDetail: this.state.storyDetail ?? null,
    }
  }

  /** Navigate to a screen, normalizing the deeper parameters away. */
  setScreen(screen, params = {}) {
    if (!SCREENS.includes(screen)) return this
    const current = this.state.params
    let next
    if (screen === 'streams') next = { ws: null, epic: null, story: null }
    else if (screen === 'workstream') next = { ws: params.ws ?? current.ws ?? null, epic: null, story: null }
    else if (screen === 'epic')
      next = { ws: params.ws ?? current.ws ?? null, epic: params.epic ?? current.epic ?? null, story: null }
    else
      next = {
        ws: params.ws ?? current.ws ?? null,
        epic: params.epic ?? current.epic ?? null,
        story: params.story ?? current.story ?? null,
      }
    this.state.params = next
    this.state.screen = deriveScreen(next)
    if (screen !== 'story') this.state.stepKey = null
    if (params.stepKey !== undefined) this.state.stepKey = params.stepKey
    this.emitChange()
    return this
  }

  openWorkstream(slug) {
    return this.setScreen('workstream', { ws: slug })
  }

  openEpic(key) {
    return this.setScreen('epic', { epic: key })
  }

  openStory(key, stepKey = null) {
    return this.setScreen('story', { story: key, stepKey })
  }

  goStreams() {
    return this.setScreen('streams')
  }

  goWorkstream() {
    return this.setScreen('workstream')
  }

  goEpic() {
    return this.setScreen('epic')
  }

  setTab(tab) {
    this.state.tab = tab === 'docs' ? 'docs' : 'epics'
    this.emitChange()
    return this
  }

  pickStep(stepKey) {
    this.state.stepKey = this.state.stepKey === stepKey ? null : stepKey
    this.emitChange()
    return this
  }

  async loadRuns() {
    if (this.disposed || typeof this.apiClient?.get !== 'function') return
    this.state.loading = true
    this.emitChange()
    try {
      const payload = await this.apiClient.get(this.listRunsPath(), { signal: this.abortController?.signal })
      if (this.disposed) return
      this.state.runs = Array.isArray(payload) ? payload : Array.isArray(payload?.items) ? payload.items : []
      this.state.error = null
    } catch (error) {
      if (!this.disposed) this.state.error = String(error?.message ?? error)
    } finally {
      if (!this.disposed) {
        this.state.loading = false
        this.emitChange()
      }
    }
  }

  async loadWorkstreams() {
    if (this.disposed || typeof this.apiClient?.get !== 'function') return
    try {
      const payload = await this.apiClient.get(this.listWorkstreamsPath(), { signal: this.abortController?.signal })
      if (this.disposed) return
      this.state.workstreams = Array.isArray(payload) ? payload : Array.isArray(payload?.items) ? payload.items : []
    } catch (error) {
      if (!this.disposed && !this.state.error) this.state.error = String(error?.message ?? error)
    } finally {
      if (!this.disposed) this.emitChange()
    }
  }

  /** Load the story detail collections (executions, oracles, edits). */
  async loadStoryDetail() {
    if (this.disposed || typeof this.apiClient?.get !== 'function' || !this.state.params.story) return
    const signal = this.abortController?.signal
    const read = async (section) => {
      try {
        const payload = await this.apiClient.get(this.storyPath(section), { signal })
        return Array.isArray(payload) ? payload : Array.isArray(payload?.items) ? payload.items : []
      } catch {
        return []
      }
    }
    const [executions, oracles, edits] = await Promise.all([read('executions'), read('oracles'), read('edits')])
    if (this.disposed) return
    this.state.storyDetail = { executions, oracles, edits }
    this.emitChange()
  }

  /** Approve (or reject) the G1 gate for the selected run. */
  async approveG1(options = {}) {
    const run = this.currentForgeRun()
    const gate = this.g1Gate()
    const evidenceSetHash = options.evidenceSetHash ?? gate?.evidenceSetHash ?? null
    const runId = options.runId ?? run?.runId ?? null
    const namespaceId = this.namespaceId
    if (this.disposed) return { ok: false, error: 'disposed' }
    if (!runId || !evidenceSetHash || !namespaceId) return { ok: false, error: 'MISSING_G1_CONTEXT' }

    const body = buildG1DecisionBody({
      evidenceSetHash,
      outcome: options.outcome ?? 'approved',
      reasonCode: options.reasonCode ?? 'intent_confirmed',
      attempt: options.attempt ?? 1,
    })
    const path = this.g1DecisionPath(runId)

    this.state.g1Approving = true
    this.state.g1Error = null
    this.emitChange()
    try {
      const result = await this.apiClient.post(path, body, { attribution: { namespaceId } })
      if (this.disposed) return { ok: false, error: 'disposed' }
      this.state.g1Approving = false
      await this.loadRuns()
      return { ok: true, result, body, path }
    } catch (error) {
      if (!this.disposed) {
        this.state.g1Approving = false
        this.state.g1Error = String(error?.message ?? error)
        this.onError(this.state.g1Error, error)
        this.emitChange()
      }
      return { ok: false, error: String(error?.message ?? error), body, path }
    }
  }

  async init() {
    if (this.disposed) return
    await Promise.all([this.loadRuns(), this.loadWorkstreams()])
    if (this.state.screen === 'story') await this.loadStoryDetail()
  }

  async refresh() {
    await this.loadRuns()
    if (this.state.screen === 'story') await this.loadStoryDetail()
  }

  teardown() {
    if (this.disposed) return
    this.disposed = true
    this.abortController?.abort?.()
    this.abortController = null
    this.onChange = () => {}
    this.onError = () => {}
  }
}

/* ------------------------------------------------------------------ mount */

/**
 * Mount the Forge console into a container element.
 *
 * @param {any} container element-like target (typically `#view-forge`)
 * @param {{
 *   namespaceId: string,
 *   apiClient: { get: Function, post: Function },
 *   params?: { ws?: string|null, epic?: string|null, story?: string|null },
 *   deliveryWorkflowId?: string,
 *   basePath?: string,
 *   SseClient?: Function,
 *   EventSource?: Function,
 *   mountActivity?: Function,
 *   mountDelivery?: Function,
 *   registerTeardown?: Function,
 * }} options
 * @returns {{ controller: ForgeCockpitController, unmount: Function, render: Function, ready: Promise<void>,
 *   isMounted: Function, getState: Function, setScreen: Function, approveG1: Function, refresh: Function }}
 */
export function mount(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('forge-cockpit.mount requires a container')
  const { namespaceId, apiClient } = options
  if (!namespaceId || typeof namespaceId !== 'string') throw new TypeError('forge-cockpit.mount requires a namespaceId')
  if (!apiClient || typeof apiClient.get !== 'function' || typeof apiClient.post !== 'function') {
    throw new TypeError('forge-cockpit.mount requires an apiClient with get() and post() methods')
  }

  const mountActivity = options.mountActivity ?? mountForgeActivity
  const mountDelivery = options.mountDelivery ?? mountDeliveryPanel

  let disposed = false
  let mountedActivity = null
  let mountedDelivery = null

  const controller = new ForgeCockpitController({
    apiClient,
    namespaceId,
    params: options.params,
    deliveryWorkflowId: options.deliveryWorkflowId,
    onChange: (state) => {
      render()
      options.onChange?.(state)
    },
  })

  const teardownSubcomponents = () => {
    if (mountedActivity) {
      try {
        mountedActivity.unmount?.()
      } catch {
        // A broken sub-component teardown must not block the next render.
      }
      mountedActivity = null
    }
    if (mountedDelivery) {
      try {
        mountedDelivery.unmount?.()
      } catch {
        // idem
      }
      mountedDelivery = null
    }
  }

  const mountSubcomponents = () => {
    const state = controller.getState()
    if (state.screen !== 'story') return
    const activityHost = container.querySelector?.('[data-forge-activity-host]')
    const deliveryHost = container.querySelector?.('[data-forge-delivery-host]')
    if (activityHost && state.storyCaseId) {
      mountedActivity = mountActivity(activityHost, {
        caseId: state.storyCaseId,
        namespaceId,
        basePath: options.basePath ?? '',
        SseClient: options.SseClient,
        EventSource: options.EventSource,
      })
    }
    if (deliveryHost && state.deliveryWorkflowId) {
      mountedDelivery = mountDelivery(deliveryHost, {
        workflowId: state.deliveryWorkflowId,
        namespaceId,
        caseId: state.storyCaseId,
        apiClient,
      })
    }
  }

  const render = () => {
    if (disposed) return
    teardownSubcomponents()
    container.innerHTML = renderForgeCockpit(controller.getState())
    mountSubcomponents()
  }

  const onClick = (event) => {
    const target = event?.target
    const closest = (selector) => target?.closest?.(selector) ?? null

    const storyCell = closest('[data-story-cell]')
    if (storyCell) {
      controller.openStory(storyCell.dataset.storyCell, storyCell.dataset.stepKey ?? null)
      return
    }
    const step = closest('[data-step-key]')
    if (step) {
      controller.pickStep(step.dataset.stepKey)
      return
    }
    const nav = closest('[data-nav]')
    if (nav) {
      controller.setScreen(nav.dataset.nav)
      return
    }
    const ws = closest('[data-ws]')
    if (ws) {
      controller.openWorkstream(ws.dataset.ws)
      return
    }
    const epic = closest('[data-epic]')
    if (epic) {
      controller.openEpic(epic.dataset.epic)
      return
    }
    const story = closest('[data-story]')
    if (story) {
      controller.openStory(story.dataset.story)
      return
    }
    const tab = closest('[data-tab]')
    if (tab) {
      controller.setTab(tab.dataset.tab)
      return
    }
    if (closest('[data-g1-approve]')) {
      void controller.approveG1()
      return
    }
    if (closest('[data-forge-refresh]')) void controller.refresh()
  }
  container.addEventListener?.('click', onClick)

  const unmount = () => {
    if (disposed) return
    disposed = true
    container.removeEventListener?.('click', onClick)
    teardownSubcomponents()
    controller.teardown()
    container.innerHTML = ''
  }

  if (typeof options.registerTeardown === 'function') options.registerTeardown(unmount)

  const ready = controller.init()
  render()

  return {
    controller,
    unmount,
    render,
    ready,
    isMounted: () => !disposed,
    getState: () => controller.getState(),
    setScreen: (screen, params) => controller.setScreen(screen, params),
    approveG1: (opts) => controller.approveG1(opts),
    refresh: () => controller.refresh(),
  }
}

export default { mount, ForgeCockpitController, renderForgeCockpit, STEPS, US_STEPS, TONES }
