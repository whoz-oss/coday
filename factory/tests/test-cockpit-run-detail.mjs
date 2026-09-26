/**
 * Factory Cockpit — Wave 2 (salve A) run-detail view — offline test suite.
 *
 * Exercises the pure-Vanilla-ESM run detail stack without a browser:
 *
 *   A. `facts.mjs`       — fact classification, flags, empty-success detection,
 *                          escaped value rendering.
 *   B. `gantt.mjs`       — lane assignment, timeline bounds, warped timeline,
 *                          deterministic row layout, ticks, escaped bar markup.
 *   C. `phase-panel.mjs` — panel rendering, evidence, enrichment + degradation.
 *   D. `run-detail.mjs`  — mount (initial load + SSE subscription), delegated
 *                          selection, live refresh and leak-free unmount.
 *
 * No external dependency, no network. Exit code 0 = all pass.
 *
 * Usage: node factory/tests/test-cockpit-run-detail.mjs
 */

import assert from 'node:assert/strict'

import {
  FACT_GROUPS,
  FLAGS,
  emptySuccess,
  collectFlags,
  renderFactGroups,
  renderValue,
  esc,
  fmtDur,
} from '../dashboard/js/components/facts.mjs'
import {
  normalizeSteps,
  laneOf,
  timelineBounds,
  buildGlobalTimeline,
  layoutLaneBars,
  buildTicks,
  laneSubtitle,
  renderBar,
  renderGantt,
  barStatus,
  toMs,
} from '../dashboard/js/components/gantt.mjs'
import { renderPhasePanel, loadPhaseEnrichment, eventSummary } from '../dashboard/js/components/phase-panel.mjs'
import { mount, WORKFLOW_UPDATED_EVENT } from '../dashboard/js/views/run-detail.mjs'
import { SseClient } from '../dashboard/js/services/sse-client.mjs'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function scenario(name, fn) {
  try {
    await fn()
    passed++
    console.log(`✓ ${name}`)
  } catch (error) {
    failed++
    console.log(`✗ ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

const flush = () => new Promise((resolve) => setImmediate(resolve))
const approx = (actual, expected, epsilon = 1e-6) => {
  assert.ok(Math.abs(actual - expected) < epsilon, `expected ${actual} ≈ ${expected}`)
}

// ---------------------------------------------------------------------------
// Fixtures — Governed Projection v2 DTOs
// ---------------------------------------------------------------------------

const NS = '11111111-1111-4111-8111-111111111111'
const WF = 'wf-cockpit-1'
const T0 = Date.parse('2026-09-26T10:00:00.000Z')
const T = (offsetMs) => new Date(T0 + offsetMs).toISOString()
const FIXED_NOW = T0 + 120_000

const workflowDto = {
  namespaceId: NS,
  state: 'existing',
  workflowId: WF,
  revision: 3,
  projectionHash: 'sha256:deadbeef',
  relations: { rootWorkflowId: WF },
  projection: {
    schemaVersion: '2',
    workflowId: WF,
    workflowType: 'us-loop',
    title: 'Cockpit Wave 2 <script>alert(1)</script>',
    status: 'completed',
    steps: [
      { id: 'preflight', name: 'preflight', status: 'completed', responsibility: { kind: 'code' } },
      {
        id: 'analyse',
        name: 'analyse <img src=x onerror=alert(1)>',
        status: 'completed',
        responsibility: { kind: 'agent', name: 'analyst' },
        facts: {
          caseId: 'case-1',
          agentsSelected: ['analyst'],
          toolCallCount: 12,
          tasks: { executed: 0 },
          wroteNothing: true,
        },
      },
      {
        id: 'implement',
        name: 'implement',
        status: 'failed',
        responsibility: { kind: 'agent', name: 'editor' },
        facts: { ticketId: 'ABC-1', timedOut: true, missingFiles: ['src/a.ts'], tasks: { executed: 4 } },
      },
      { id: 'verify', name: 'verify', status: 'running', responsibility: { kind: 'code' } },
    ],
  },
}

const timingDto = {
  namespaceId: NS,
  workflowId: WF,
  timing: {
    complete: true,
    incompleteReasons: [],
    createdAt: T(0),
    lastActivityAt: T(120_000),
    totalElapsedMs: 120_000,
    startedAt: T(0),
    firstStartedAt: T(0),
    firstCompletedAt: T(120_000),
    lastCompletedAt: T(120_000),
    activeMs: 90_000,
    waitingHumanMs: 20_000,
    blockedMs: 10_000,
    transitionCount: 8,
    currentStatus: 'completed',
    currentStatusSince: T(120_000),
    steps: [
      {
        stepId: 'preflight',
        firstStartedAt: T(0),
        activeMs: 10_000,
        waitingHumanMs: 0,
        blockedMs: 0,
        currentStatus: 'completed',
      },
      {
        stepId: 'analyse',
        firstStartedAt: T(10_000),
        activeMs: 50_000,
        waitingHumanMs: 0,
        blockedMs: 0,
        currentStatus: 'completed',
      },
      {
        stepId: 'implement',
        firstStartedAt: T(60_000),
        activeMs: 30_000,
        waitingHumanMs: 0,
        blockedMs: 0,
        currentStatus: 'failed',
      },
      {
        stepId: 'verify',
        firstStartedAt: T(90_000),
        activeMs: 30_000,
        waitingHumanMs: 0,
        blockedMs: 0,
        currentStatus: 'running',
      },
    ],
  },
}

const evidenceDto = {
  namespaceId: NS,
  workflowId: WF,
  items: [
    {
      evidenceId: 'ev-1',
      namespaceId: NS,
      workflowId: WF,
      stepId: 'implement',
      kind: 'oracle-result',
      outcome: 'fail',
      facts: { exitCode: 1 },
      observedAt: T(90_000),
    },
    {
      evidenceId: 'ev-2',
      namespaceId: NS,
      workflowId: WF,
      stepId: 'preflight',
      kind: 'artifact',
      facts: {},
      observedAt: T(1_000),
    },
  ],
}

const metricsDto = {
  scope: { rootWorkflowId: WF, includedWorkflowIds: [WF] },
  metrics: { cycleTime: { available: true, complete: false, value: { durationMs: 60_000 } } },
  capabilities: { cost: { available: false } },
}

const caseEventsFixture = [
  {
    id: 'm1',
    type: 'MessageEvent',
    actor: { role: 'USER' },
    content: [{ content: 'Brief depuis Jira' }],
    timestamp: T(1_000),
  },
  { id: 'm2', type: 'CaseStatusEvent', status: 'completed', timestamp: T(2_000) },
]

const clone = (value) => JSON.parse(JSON.stringify(value))

// ---------------------------------------------------------------------------
// A. facts.mjs
// ---------------------------------------------------------------------------

console.log('\nfacts.mjs')

await scenario('esc() escapes every HTML-significant character', () => {
  assert.equal(esc('<a href="x">\'&\n'), '&lt;a href=&quot;x&quot;&gt;&#39;&amp;\n')
  assert.equal(esc(null), '')
  assert.equal(esc(42), '42')
})

await scenario('fmtDur() formats milliseconds, seconds and minutes', () => {
  assert.equal(fmtDur(null), '—')
  assert.equal(fmtDur(850), '850ms')
  assert.equal(fmtDur(15_000), '15.0s')
  assert.equal(fmtDur(120_000), '2m00s')
  assert.equal(fmtDur(90_000), '1m30s')
})

await scenario('FACT_GROUPS/FLAGS keep their documented shape', () => {
  assert.ok(Array.isArray(FACT_GROUPS) && FACT_GROUPS.length >= 4)
  for (const group of FACT_GROUPS) {
    assert.equal(typeof group.title, 'string')
    assert.ok(Array.isArray(group.keys) && group.keys.length > 0)
  }
  const known = new Set(FACT_GROUPS.flatMap((group) => group.keys))
  for (const key of ['exitCode', 'filesModified', 'caseId', 'agentName']) assert.ok(known.has(key), key)
  assert.ok(Array.isArray(FLAGS) && FLAGS.length >= 5)
  for (const flag of FLAGS) {
    assert.equal(typeof flag.key, 'string')
    assert.equal(typeof flag.when, 'function')
    assert.equal(typeof flag.icon, 'string')
    assert.equal(typeof flag.label, 'string')
    assert.ok(['bad', 'warn'].includes(flag.level))
  }
  assert.deepEqual(
    FLAGS.map((flag) => flag.key),
    ['wroteNothing', 'timedOut', 'killedByBudget', 'claimsMatch', 'missingFiles']
  )
})

await scenario('FLAGS predicates discriminate true / false / non-empty arrays', () => {
  const byKey = new Map(FLAGS.map((flag) => [flag.key, flag]))
  assert.equal(byKey.get('wroteNothing').when(true), true)
  assert.equal(byKey.get('wroteNothing').when(false), false)
  assert.equal(byKey.get('claimsMatch').when(false), true)
  assert.equal(byKey.get('claimsMatch').when(true), false)
  assert.equal(byKey.get('missingFiles').when(['a']), true)
  assert.equal(byKey.get('missingFiles').when([]), false)
  assert.equal(byKey.get('timedOut').when(true), true)
  assert.equal(byKey.get('killedByBudget').when(true), true)
})

await scenario('collectFlags() returns only the flags actually raised', () => {
  const flags = collectFlags({
    facts: { wroteNothing: true, timedOut: true, missingFiles: ['x'], claimsMatch: true, fileCount: 2 },
  })
  assert.deepEqual(
    flags.map((flag) => flag.key),
    ['wroteNothing', 'timedOut', 'missingFiles']
  )
  assert.deepEqual(collectFlags({ facts: {} }), [])
  assert.deepEqual(collectFlags(null), [])
})

await scenario('emptySuccess() flags a positive verdict that executed nothing', () => {
  assert.equal(emptySuccess({ status: 'pass', facts: { tasks: { executed: 0 } } }), true)
  assert.equal(emptySuccess({ status: 'completed', facts: { tasks: { executed: 0 } } }), true)
  assert.equal(emptySuccess({ status: 'pass', facts: { tasks: { executed: 3 } } }), false)
  assert.equal(emptySuccess({ status: 'fail', facts: { tasks: { executed: 0 } } }), false)
  assert.equal(emptySuccess({ status: 'pass', facts: {} }), false)
  assert.equal(emptySuccess(null), false)
})

await scenario('renderFactGroups() classifies known keys and keeps unknown ones visible', () => {
  const html = renderFactGroups({ exitCode: 0, customFact: 'kept', filesModified: ['a.ts'] })
  assert.ok(html.includes('Verdict'))
  assert.ok(html.includes('Fichiers'))
  assert.ok(html.includes('Autres'))
  assert.ok(html.includes('customFact'))
  assert.ok(html.includes('a.ts'))
  assert.equal(renderFactGroups({}), '<span class="nil">Aucun fait enregistré.</span>')
  assert.equal(renderFactGroups(null), '<span class="nil">Aucun fait enregistré.</span>')
})

await scenario('renderValue()/renderFactGroups() escape hostile fact values', () => {
  assert.ok(renderValue('summary', '</div><script>alert(1)</script>').includes('&lt;/div&gt;&lt;script&gt;'))
  const html = renderFactGroups({ custom: '<img src=x onerror=alert(1)>' })
  assert.ok(!html.includes('<img'))
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'))
})

// ---------------------------------------------------------------------------
// B. gantt.mjs
// ---------------------------------------------------------------------------

console.log('\ngantt.mjs')

await scenario('barStatus()/toMs() map the projection v2 vocabulary', () => {
  assert.equal(barStatus('completed'), 'pass')
  assert.equal(barStatus('failed'), 'fail')
  assert.equal(barStatus('cancelled'), 'fail')
  assert.equal(barStatus('running'), 'running')
  assert.equal(barStatus('waiting_human'), 'blocked')
  assert.equal(barStatus('pending'), 'pending')
  assert.equal(toMs(T(10_000)), T0 + 10_000)
  assert.ok(Number.isNaN(toMs('not-a-date')))
  assert.ok(Number.isNaN(toMs(undefined)))
})

await scenario('normalizeSteps() reads the v2 workflow + timing DTOs', () => {
  const steps = normalizeSteps(workflowDto, timingDto.timing)
  assert.deepEqual(
    steps.map((step) => step.id),
    ['preflight', 'analyse', 'implement', 'verify']
  )
  const analyse = steps.find((step) => step.id === 'analyse')
  assert.equal(analyse.phaseKind, 'agent')
  assert.equal(analyse.status, 'pass')
  assert.equal(analyse.projectionStatus, 'completed')
  assert.equal(analyse.startedAt, T(10_000))
  assert.equal(analyse.durationMs, 50_000)
  assert.equal(analyse.facts.caseId, 'case-1')

  const verify = steps.find((step) => step.id === 'verify')
  assert.equal(verify.status, 'running')
  assert.equal(verify.running, true)
  assert.equal(verify.durationMs, 30_000)
})

await scenario('normalizeSteps() tolerates an absent timing projection', () => {
  const steps = normalizeSteps({ projection: { steps: workflowDto.projection.steps } }, null)
  assert.equal(steps.length, 4)
  for (const step of steps) assert.equal(step.durationMs, undefined)
  assert.equal(steps[0].startedAt, null)
})

await scenario('laneOf() maps code steps to the orchestrator and agents to their lane', () => {
  const steps = normalizeSteps(workflowDto, timingDto.timing)
  const byId = new Map(steps.map((step) => [step.id, step]))
  assert.deepEqual(laneOf(byId.get('preflight')), { id: '__orchestrator', name: 'orchestrateur', kind: 'code' })
  assert.equal(laneOf(byId.get('analyse')).id, 'analyst')
  assert.equal(laneOf(byId.get('implement')).id, 'editor')

  assert.equal(laneOf({ phaseKind: 'agent', name: 'analyse x', facts: { agentName: 'named-agent' } }).id, 'named-agent')
  assert.equal(laneOf({ phaseKind: 'agent', name: 'analyse x', facts: {} }).id, 'analyste')
  assert.equal(laneOf({ phaseKind: 'agent', name: 'implement x', facts: {} }).id, 'agent')
  assert.equal(laneOf({ phaseKind: 'human', name: 'approve', facts: {} }).id, '__orchestrator')
})

await scenario('timelineBounds() uses timing.startedAt as the origin', () => {
  const steps = normalizeSteps(workflowDto, timingDto.timing)
  const bounds = timelineBounds(workflowDto, steps, timingDto.timing, FIXED_NOW)
  assert.equal(bounds.t0, T0)
  assert.equal(bounds.span, 120_000)
})

await scenario('buildGlobalTimeline() warps with a 2.5% minimum gap, deterministically', () => {
  const steps = normalizeSteps(workflowDto, timingDto.timing)
  const first = buildGlobalTimeline(workflowDto, steps, timingDto.timing, FIXED_NOW)
  const second = buildGlobalTimeline(workflowDto, steps, timingDto.timing, FIXED_NOW)
  assert.equal(first.span, 120_000)
  assert.deepEqual([...first.positions], [...second.positions])
  assert.equal(first.positions.get('preflight'), 0)
  approx(first.positions.get('analyse'), (10_000 / 120_000) * 100)
  approx(first.positions.get('implement'), 50)
  approx(first.positions.get('verify'), 75)
  assert.deepEqual(
    first.anchors.map((anchor) => anchor.label),
    ['0ms', '10.0s', '1m00s', '1m30s']
  )
})

await scenario('timelineBounds() floors the span at 1000ms when nothing is measured', () => {
  const bounds = timelineBounds(workflowDto, [], null, T0)
  assert.equal(bounds.t0, T0)
  assert.equal(bounds.span, 1000)
})

await scenario('layoutLaneBars() keeps chronological starts and rows short steps deterministically', () => {
  const steps = normalizeSteps(workflowDto, timingDto.timing)
  const timeline = buildGlobalTimeline(workflowDto, steps, timingDto.timing, FIXED_NOW)
  const lane = steps.filter((step) => step.id === 'preflight' || step.id === 'verify')
  const layout = layoutLaneBars(lane, timeline, workflowDto, FIXED_NOW)
  assert.equal(layout.items.length, 2)
  assert.equal(layout.height, 62)
  const [first, second] = layout.items
  assert.equal(first.step.id, 'preflight')
  assert.equal(first.row, 0)
  approx(first.left, 0)
  approx(first.width, (10_000 / 120_000) * 100)
  assert.equal(first.compact, false)
  assert.equal(second.step.id, 'verify')
  assert.equal(second.row, 0)
  approx(second.left, 75)
  approx(second.width, 25)
  assert.equal(second.top, 12)
})

await scenario('layoutLaneBars() never lets a bar overflow the plot', () => {
  const timeline = { t0: 0, span: 1000, positions: new Map([['late', 99]]) }
  const layout = layoutLaneBars(
    [{ id: 'late', name: 'late', startedAt: new Date(990).toISOString(), durationMs: 500, status: 'pass', facts: {} }],
    timeline,
    workflowDto,
    1000
  )
  assert.equal(layout.items.length, 1)
  approx(layout.items[0].left, 99)
  approx(layout.items[0].width, 1)
  assert.equal(layout.items[0].compact, true)
})

await scenario('buildTicks() picks a readable step and spans the axis', () => {
  const ticks = buildTicks(120_000)
  assert.equal(ticks.length, 9)
  assert.equal(ticks[0].pct, 0)
  assert.equal(ticks[0].label, '0ms')
  approx(ticks[ticks.length - 1].pct, 100)
  assert.equal(ticks[ticks.length - 1].label, '2m00s')
  assert.deepEqual(buildTicks(0), [{ pct: 0, label: '0ms' }])
})

await scenario('laneSubtitle() reports step counts without inventing an agent model', () => {
  assert.equal(laneSubtitle({ id: '__orchestrator' }, [], workflowDto), 'us-loop')
  assert.equal(laneSubtitle({ id: 'analyst' }, [1, 2, 3], workflowDto), '3 étapes')
  assert.equal(laneSubtitle({ id: 'analyst' }, [1], workflowDto), '1 étape')
  assert.equal(laneSubtitle(null, [], workflowDto), '')
})

await scenario('renderBar() escapes step names, ids, tooltips and attributes', () => {
  const hostile = {
    id: 'evil" onmouseover="x',
    name: '"><script>alert(1)</script>',
    status: 'pass',
    durationMs: 5000,
    facts: { wroteNothing: true },
  }
  const html = renderBar(hostile, { left: 10, width: 20, top: 0, compact: false }, null)
  assert.ok(!html.includes('<script'))
  assert.ok(!html.includes('onmouseover="x"'))
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))
  assert.ok(html.includes('data-step-id="evil&quot; onmouseover=&quot;x"'))
  assert.ok(html.includes('class="bar pass"'))
  assert.ok(html.includes('aucune écriture'))
})

await scenario('renderBar() marks a selection and appends the empty-success flag', () => {
  const step = {
    id: 'analyse',
    name: 'analyse',
    status: 'pass',
    durationMs: 1000,
    facts: { tasks: { executed: 0 } },
  }
  const html = renderBar(step, { left: 0, width: 20, top: 12, compact: false }, 'analyse')
  assert.ok(html.includes('selected'))
  assert.ok(html.includes('aria-pressed="true"'))
  assert.ok(html.includes('succès vide — aucune tâche exécutée'))
})

await scenario('renderGantt() groups steps per lane and escapes the payload', () => {
  const steps = normalizeSteps(workflowDto, timingDto.timing)
  const html = renderGantt({
    workflow: workflowDto,
    timing: timingDto.timing,
    steps,
    selectedStepId: null,
    now: FIXED_NOW,
  })
  assert.ok(html.includes('data-gantt="true"'))
  for (const id of ['preflight', 'analyse', 'implement', 'verify']) {
    assert.ok(html.includes(`data-step-id="${id}"`), id)
  }
  assert.ok(html.includes('orchestrateur'))
  assert.ok(html.includes('analyst'))
  assert.ok(html.includes('editor'))
  assert.ok(!html.includes('<img'))
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'))
})

await scenario('renderGantt() renders an explicit empty state without timestamps', () => {
  const html = renderGantt({ workflow: { projection: { steps: [] } }, timing: null, now: FIXED_NOW })
  assert.ok(html.includes('data-gantt-empty="true"'))
})

// ---------------------------------------------------------------------------
// C. phase-panel.mjs
// ---------------------------------------------------------------------------

console.log('\nphase-panel.mjs')

const analyseStep = () => normalizeSteps(workflowDto, timingDto.timing).find((step) => step.id === 'analyse')

await scenario('renderPhasePanel() shows a placeholder when nothing is selected', () => {
  const html = renderPhasePanel({ step: null })
  assert.ok(html.includes('data-phase-panel="true"'))
  assert.ok(html.includes('Sélectionner une étape'))
})

await scenario('renderPhasePanel() renders header, facts and evidence for a step', () => {
  const html = renderPhasePanel({
    step: analyseStep(),
    workflow: workflowDto,
    evidence: evidenceDto.items,
    enrichment: null,
  })
  assert.ok(html.includes('data-step-id="analyse"'))
  assert.ok(html.includes('data-step-status="pass"'))
  assert.ok(html.includes('data-step-kind="agent"'))
  assert.ok(html.includes('Verdict') || html.includes('Tour d’agent'))
  // Evidence is filtered by stepId: analyse has none, implement does.
  assert.ok(html.includes('Aucune preuve enregistrée'))
})

await scenario('renderPhasePanel() shows evidence attached to the selected step', () => {
  const implement = normalizeSteps(workflowDto, timingDto.timing).find((step) => step.id === 'implement')
  const html = renderPhasePanel({ step: implement, workflow: workflowDto, evidence: evidenceDto.items })
  assert.ok(html.includes('oracle-result'))
  assert.ok(html.includes('exitCode=1'))
  assert.ok(!html.includes('artifact'))
})

await scenario('renderPhasePanel() escapes facts, ticket content and notices', () => {
  const step = {
    id: 'implement',
    name: 'implement',
    phaseKind: 'agent',
    status: 'fail',
    durationMs: 1000,
    facts: { ticketId: 'ABC-1', summary: '<b>x</b>' },
  }
  const html = renderPhasePanel({
    step,
    workflow: workflowDto,
    evidence: [],
    enrichment: {
      caseId: null,
      ticketId: 'ABC-1',
      ticket: { ticketContent: '<script>bad</script>', fetchedAt: T(1_000) },
      notices: [{ label: 'Contenu du ticket non disponible', message: '<img src=x>' }],
    },
  })
  assert.ok(!html.includes('<script>bad'))
  assert.ok(html.includes('&lt;script&gt;bad&lt;/script&gt;'))
  assert.ok(!html.includes('<b>x</b>'))
  assert.ok(html.includes('&lt;b&gt;x&lt;/b&gt;'))
  assert.ok(!html.includes('<img src=x>'))
  assert.ok(html.includes('&lt;img src=x&gt;'))
})

await scenario('renderPhasePanel() renders a case event stream when provided', () => {
  const html = renderPhasePanel({
    step: analyseStep(),
    workflow: workflowDto,
    evidence: [],
    enrichment: { caseId: 'case-1', caseEvents: caseEventsFixture, ticket: null, notices: [] },
  })
  assert.ok(html.includes('Brief envoyé'))
  assert.ok(html.includes('Brief depuis Jira'))
  assert.ok(html.includes('Événements (2)'))
})

await scenario('eventSummary() summarises known event types', () => {
  assert.equal(eventSummary({ type: 'CaseStatusEvent', status: 'completed' }), 'completed')
  assert.ok(
    eventSummary({ type: 'MessageEvent', actor: { role: 'AGENT' }, content: [{ content: 'hi' }] }).includes('hi')
  )
  assert.ok(
    eventSummary({ type: 'ToolResponseEvent', toolName: 'grep', success: false, durationMs: 12 }).startsWith('✗')
  )
  assert.equal(eventSummary(null), '')
})

await scenario('loadPhaseEnrichment() degrades gracefully when sources are unavailable', async () => {
  const failing = {
    async get() {
      throw new Error('AgentOS injoignable')
    },
  }
  const step = analyseStep()
  step.facts = { ...step.facts, ticketId: 'ABC-1' }
  const result = await loadPhaseEnrichment(step, { apiClient: failing })
  assert.equal(result.caseEvents, null)
  assert.equal(result.ticket, null)
  assert.equal(result.notices.length, 2)
  assert.equal(result.notices[0].label, 'AgentOS non disponible')
  assert.equal(result.notices[1].label, 'Contenu du ticket non disponible')
})

await scenario('loadPhaseEnrichment() returns both sources when available', async () => {
  const client = {
    async get(path) {
      if (path.startsWith('/api/cases/')) return clone(caseEventsFixture)
      if (path.startsWith('/api/factory/jira/')) return { ticketContent: 'ok', fetchedAt: T(1) }
      throw new Error(`unexpected ${path}`)
    },
  }
  const step = analyseStep()
  step.facts = { ...step.facts, ticketId: 'ABC-1' }
  const result = await loadPhaseEnrichment(step, { apiClient: client })
  assert.equal(result.caseEvents.length, 2)
  assert.equal(result.ticket.ticketContent, 'ok')
  assert.deepEqual(result.notices, [])
})

// ---------------------------------------------------------------------------
// D. run-detail.mjs
// ---------------------------------------------------------------------------

console.log('\nrun-detail.mjs')

class FakeContainer {
  constructor() {
    this.innerHTML = ''
    this.listeners = new Map()
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(handler)
  }

  removeEventListener(type, handler) {
    this.listeners.get(type)?.delete(handler)
  }

  dispatch(type, event) {
    for (const handler of [...(this.listeners.get(type) ?? [])]) handler(event)
  }

  listenerCount() {
    let total = 0
    for (const handlers of this.listeners.values()) total += handlers.size
    return total
  }
}

function createTimerSpy() {
  let sequence = 0
  const pending = new Map()
  return {
    pending,
    setTimeoutFn: (fn, ms) => {
      const id = ++sequence
      pending.set(id, { fn, ms })
      return id
    },
    clearTimeoutFn: (id) => pending.delete(id),
    flush: () => {
      const entries = [...pending.values()]
      pending.clear()
      for (const { fn } of entries) fn()
    },
  }
}

function createApiClient(options = {}) {
  const calls = []
  const fail = options.fail ?? {}
  const base = `/api/factory/workflows/${WF}`
  const detail = `${base}?namespaceId=${NS}`
  return {
    calls,
    async get(path, requestOptions = {}) {
      calls.push(path)
      if (requestOptions.signal?.aborted) {
        const error = new Error('aborted')
        error.name = 'AbortError'
        throw error
      }
      if (path === detail) {
        if (fail.detail) throw new Error(fail.detail)
        return clone(workflowDto)
      }
      if (path === `${base}/timing?namespaceId=${NS}`) return clone(timingDto)
      if (path === `${base}/evidence?namespaceId=${NS}`) {
        if (fail.evidence) throw new Error('evidence down')
        return clone(evidenceDto)
      }
      if (path === `${base}/metrics?namespaceId=${NS}`) {
        if (fail.metrics) throw new Error('metrics down')
        return clone(metricsDto)
      }
      if (path.startsWith('/api/cases/')) {
        if (fail.cases) throw new Error('AgentOS injoignable')
        return clone(caseEventsFixture)
      }
      if (path.startsWith('/api/factory/jira/')) {
        if (fail.jira) throw new Error('Jira non configuré')
        return { ticketContent: 'ticket body', fetchedAt: T(1_000) }
      }
      throw new Error(`unexpected path ${path}`)
    },
  }
}

const baseMountOptions = (apiClient, sseClient) => ({
  workflowId: WF,
  namespaceId: NS,
  apiClient,
  sseClient,
  now: () => FIXED_NOW,
})

await scenario('mount() validates its inputs', async () => {
  await assert.rejects(() => mount(null, {}), TypeError)
  await assert.rejects(() => mount(new FakeContainer(), { namespaceId: NS, apiClient: createApiClient() }), TypeError)
  await assert.rejects(() => mount(new FakeContainer(), { workflowId: WF, apiClient: createApiClient() }), TypeError)
  await assert.rejects(() => mount(new FakeContainer(), { workflowId: WF, namespaceId: NS }), TypeError)
})

await scenario('mount() loads the projection, timing, evidence and metrics', async () => {
  const container = new FakeContainer()
  const apiClient = createApiClient()
  const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
  const timers = createTimerSpy()
  const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })

  assert.equal(apiClient.calls.length, 4)
  assert.ok(apiClient.calls.some((path) => path.includes('/timing?')))
  assert.ok(apiClient.calls.some((path) => path.includes('/evidence?')))
  assert.ok(apiClient.calls.some((path) => path.includes('/metrics?')))

  assert.ok(container.innerHTML.includes('data-run-detail="true"'))
  assert.ok(container.innerHTML.includes('data-gantt="true"'))
  assert.ok(container.innerHTML.includes('data-step-id="preflight"'))
  assert.ok(container.innerHTML.includes('data-metrics="true"'))
  assert.ok(container.innerHTML.includes('cycleTime'))
  assert.ok(!container.innerHTML.includes('<script>alert(1)</script>'))
  assert.ok(container.innerHTML.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))
  assert.equal(container.listenerCount(), 1)

  handle.unmount()
  sseClient.close()
})

await scenario('mount() subscribes to workflow-projection-updated only', async () => {
  const container = new FakeContainer()
  const apiClient = createApiClient()
  const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
  const timers = createTimerSpy()
  const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })

  assert.equal(WORKFLOW_UPDATED_EVENT, 'workflow-projection-updated')
  assert.deepEqual([...sseClient.listeners.keys()], ['workflow-projection-updated'])
  assert.equal(sseClient.listeners.get('workflow-projection-updated').size, 1)

  handle.unmount()
  sseClient.close()
})

await scenario('clicking a bar selects the step and loads its enrichment', async () => {
  const container = new FakeContainer()
  const apiClient = createApiClient()
  const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
  const timers = createTimerSpy()
  const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })

  container.dispatch('click', { target: { dataset: { stepId: 'analyse' } } })
  assert.equal(handle.getState().selectedStepId, 'analyse')
  assert.ok(container.innerHTML.includes('data-phase-panel="true"'))
  assert.ok(container.innerHTML.includes('data-step-id="analyse"'))

  await flush()
  assert.ok(apiClient.calls.some((path) => path === '/api/cases/case-1/events'))
  assert.ok(container.innerHTML.includes('Brief depuis Jira'))
  assert.ok(container.innerHTML.includes('Événements (2)'))

  // A second click on the same bar deselects it.
  container.dispatch('click', { target: { dataset: { stepId: 'analyse' } } })
  assert.equal(handle.getState().selectedStepId, null)
  assert.ok(container.innerHTML.includes('Sélectionner une étape'))

  handle.unmount()
  sseClient.close()
})

await scenario('a matching SSE event debounces exactly one refresh', async () => {
  const container = new FakeContainer()
  const apiClient = createApiClient()
  const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
  const timers = createTimerSpy()
  const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })
  const before = apiClient.calls.length

  sseClient.emit(WORKFLOW_UPDATED_EVENT, { workflowId: WF, namespaceId: NS, revision: 4 })
  sseClient.emit(WORKFLOW_UPDATED_EVENT, { workflowId: WF, namespaceId: NS, revision: 5 })
  assert.equal(timers.pending.size, 1)
  assert.notEqual(handle.getPendingTimer(), null)

  // A different workflow must not trigger a refresh.
  sseClient.emit(WORKFLOW_UPDATED_EVENT, { workflowId: 'other', namespaceId: NS, revision: 9 })
  sseClient.emit(WORKFLOW_UPDATED_EVENT, { workflowId: WF, namespaceId: 'other-ns', revision: 9 })
  assert.equal(timers.pending.size, 1)

  timers.flush()
  assert.equal(handle.getPendingTimer(), null)
  await flush()
  assert.equal(apiClient.calls.length, before + 4)

  handle.unmount()
  sseClient.close()
})

await scenario('unmount() is leak-free: timers, SSE, DOM listener and aborts', async () => {
  const created = []
  const PreviousAbortController = globalThis.AbortController
  class TrackingAbortController extends PreviousAbortController {
    constructor() {
      super()
      created.push(this)
    }
  }
  globalThis.AbortController = TrackingAbortController

  try {
    const container = new FakeContainer()
    const apiClient = createApiClient()
    const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
    const timers = createTimerSpy()
    const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })

    sseClient.emit(WORKFLOW_UPDATED_EVENT, { workflowId: WF, namespaceId: NS, revision: 4 })
    assert.equal(timers.pending.size, 1)
    assert.equal(container.listenerCount(), 1)
    assert.equal(sseClient.listeners.size, 1)

    handle.unmount()
    assert.equal(handle.isMounted(), false)
    assert.equal(handle.getPendingTimer(), null)
    assert.equal(timers.pending.size, 0, 'no lingering refresh timer')
    assert.equal(container.listenerCount(), 0, 'no lingering DOM listener')
    assert.equal(sseClient.listeners.size, 0, 'no lingering SSE listener')
    assert.equal(container.innerHTML, '')
    assert.equal(handle.getState().abortController, null)
    assert.equal(created.at(-1).signal.aborted, true, 'in-flight request aborted')

    // Idempotent, and a post-unmount event cannot revive the view.
    handle.unmount()
    sseClient.emit(WORKFLOW_UPDATED_EVENT, { workflowId: WF, namespaceId: NS, revision: 6 })
    container.dispatch('click', { target: { dataset: { stepId: 'analyse' } } })
    assert.equal(timers.pending.size, 0)
    assert.equal(container.innerHTML, '')

    sseClient.close()
  } finally {
    globalThis.AbortController = PreviousAbortController
  }
})

await scenario('mount() surfaces a projection failure without leaking', async () => {
  const container = new FakeContainer()
  const apiClient = createApiClient({ fail: { detail: 'projection indisponible' } })
  const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
  const timers = createTimerSpy()
  const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })

  assert.equal(handle.getState().phase, 'error')
  assert.ok(container.innerHTML.includes('data-run-detail-error="true"'))
  assert.ok(container.innerHTML.includes('projection indisponible'))

  handle.unmount()
  sseClient.close()
})

await scenario('mount() degrades when optional evidence/metrics endpoints fail', async () => {
  const container = new FakeContainer()
  const apiClient = createApiClient({ fail: { evidence: true, metrics: true } })
  const sseClient = new SseClient('/api/factory/workflows/stream?namespaceId=' + NS)
  const timers = createTimerSpy()
  const handle = await mount(container, { ...baseMountOptions(apiClient, sseClient), ...timers })

  assert.equal(handle.getState().phase, 'ready')
  assert.ok(container.innerHTML.includes('data-gantt="true"'))
  assert.ok(!container.innerHTML.includes('data-metrics="true"'))
  assert.ok(!container.innerHTML.includes('data-run-detail-error="true"'))

  handle.unmount()
  sseClient.close()
})

// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
