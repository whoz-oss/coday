/**
 * Factory Cockpit — temporal-lanes waterfall unit tests (vanilla, Node built-in
 * test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/components/temporal-lanes.test.mjs
 *
 * Covers the three contracts of the SSSF waterfall:
 *   - lane classification (engineer / code / one lane per distinct agent);
 *   - real-time layout (positions from startedAt/durationMs, minimum width
 *     floor, non-overlapping sequential blocks, normalization to 100%);
 *   - the literal `-` for every metric the Factory projection does not carry.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DASH,
  classifyActorKind,
  classifyLane,
  actorName,
  buildWaterfallLayout,
  renderWaterfallTimeline,
  renderRunStrip,
  renderWaterfall,
  buildTicks,
  axisLabel,
} from './temporal-lanes.mjs'

const T0 = '2026-01-01T00:00:00.000Z'

/** ISO instant offset by `seconds` from T0. */
function at(seconds) {
  return new Date(Date.parse(T0) + seconds * 1000).toISOString()
}

function step(overrides) {
  return { id: 'x', name: 'x', lane: 'code', status: 'completed', ...overrides }
}

// ------------------------------------------------------------- classification

test('classifyActorKind prefers explicit lane, then responsibility kind', () => {
  assert.equal(classifyActorKind({ lane: 'human' }), 'human')
  assert.equal(classifyActorKind({ responsibility: { kind: 'code' } }), 'code')
  assert.equal(classifyActorKind({ lane: 'agent' }), 'agent')
  assert.equal(classifyActorKind({ id: 'run-tests' }), 'code')
  assert.equal(classifyActorKind({ id: 'unclassified-step' }), 'agent')
})

test('classifyLane maps human to engineer, code to code, and agents to named lanes', () => {
  assert.deepEqual(classifyLane({ lane: 'human' }), { id: 'engineer', kind: 'human', label: 'engineer' })
  assert.deepEqual(classifyLane({ lane: 'code' }), { id: 'code', kind: 'code', label: 'code' })
  assert.deepEqual(classifyLane({ lane: 'agent', responsibility: { kind: 'agent', name: 'analyst' } }), {
    id: 'agent:analyst',
    kind: 'agent',
    label: 'analyst',
  })
})

test('actorName falls back to "agent" when the projection names no actor', () => {
  assert.equal(actorName({ lane: 'agent' }), 'agent')
  assert.equal(actorName({ lane: 'agent', responsibility: { name: 'editor' } }), 'editor')
})

// -------------------------------------------------------------------- layout

test('blocks are positioned in real time from startedAt and durationMs', () => {
  const layout = buildWaterfallLayout([
    step({ id: 'a', name: 'A', startedAt: at(0), durationMs: 30000 }),
    step({ id: 'b', name: 'B', startedAt: at(30), durationMs: 60000 }),
  ])

  const code = layout.lanes.find((lane) => lane.id === 'code')
  const [a, b] = code.blocks
  assert.equal(a.leftPct, 0)
  assert.equal(a.widthPct, 33.3333)
  assert.equal(b.leftPct, 33.3333)
  assert.equal(b.widthPct, 66.6667)
})

test('the engineer lane is always present, code only when code steps exist', () => {
  const layout = buildWaterfallLayout([
    step({ id: 'a', lane: 'agent', responsibility: { kind: 'agent', name: 'analyst' } }),
  ])
  const ids = layout.lanes.map((lane) => lane.id)
  assert.deepEqual(ids, ['engineer', 'agent:analyst'])
})

test('one lane per distinct agent, in first-appearance order', () => {
  const layout = buildWaterfallLayout([
    step({ id: '1', lane: 'agent', responsibility: { kind: 'agent', name: 'analyst' } }),
    step({ id: '2', lane: 'agent', responsibility: { kind: 'agent', name: 'editor' } }),
    step({ id: '3', lane: 'agent', responsibility: { kind: 'agent', name: 'analyst' } }),
    step({ id: '4', lane: 'code' }),
  ])
  assert.deepEqual(
    layout.lanes.map((lane) => lane.id),
    ['engineer', 'code', 'agent:analyst', 'agent:editor']
  )
  const analyst = layout.lanes.find((lane) => lane.id === 'agent:analyst')
  assert.equal(analyst.blocks.length, 2)
})

test('a very short block gets the minimum width floor', () => {
  const layout = buildWaterfallLayout([
    step({ id: 'tiny', name: 'commit', startedAt: at(0), durationMs: 1 }),
    step({ id: 'next', name: 'next', startedAt: at(1), durationMs: 1000 }),
  ])
  const tiny = layout.lanes.find((lane) => lane.id === 'code').blocks.find((block) => block.id === 'tiny')
  assert.ok(tiny.widthPct >= 3, `expected floor width >= 3, got ${tiny.widthPct}`)
})

test('sequential blocks never overlap', () => {
  const layout = buildWaterfallLayout([
    step({ id: 'a', startedAt: at(0), durationMs: 1 }),
    step({ id: 'b', startedAt: at(0), durationMs: 1 }),
    step({ id: 'c', startedAt: at(0), durationMs: 1 }),
  ])
  const blocks = layout.lanes.find((lane) => lane.id === 'code').blocks
  for (let i = 1; i < blocks.length; i += 1) {
    const previousRight = blocks[i - 1].leftPct + blocks[i - 1].widthPct
    assert.ok(
      blocks[i].leftPct >= previousRight - 1e-6,
      `block ${i} starts at ${blocks[i].leftPct}, previous ends at ${previousRight}`
    )
  }
})

test('the whole strip is normalized into [0, 100]', () => {
  const many = Array.from({ length: 50 }, (_, index) =>
    step({ id: `s${index}`, startedAt: at(index), durationMs: 1000 })
  )
  const layout = buildWaterfallLayout(many)
  const blocks = layout.lanes.flatMap((lane) => lane.blocks)
  for (const block of blocks) {
    assert.ok(block.leftPct >= 0, `left ${block.leftPct}`)
    assert.ok(block.leftPct + block.widthPct <= 100.001, `right edge ${block.leftPct + block.widthPct}`)
  }
  const maxRight = Math.max(...blocks.map((block) => block.leftPct + block.widthPct))
  assert.ok(Math.abs(maxRight - 100) < 0.01, `expected a full strip, got ${maxRight}`)
})

test('controller request is the first non-governed engineer activity and ends at the first real start', () => {
  const request = {
    text: '<implement safely>',
    observedAt: at(0),
    actorId: 'engineer-1',
    source: 'factory-cockpit',
    namespaceId: 'ns-1',
  }
  const layout = buildWaterfallLayout({ controllerRequest: request, steps: [step({ id: 'first', startedAt: at(10), durationMs: 1000 })] })
  const engineer = layout.lanes.find((lane) => lane.id === 'engineer')
  assert.equal(engineer.blocks[0].id, 'controller-request')
  assert.equal(engineer.blocks[0].durationMs, 10000)
  assert.equal(engineer.blocks[0].nonGoverned, true)
  assert.ok(renderWaterfall(layout).includes('data-non-governed="true"'))
})

test('controller request without a started workflow step stays a minimum-width milestone', () => {
  const layout = buildWaterfallLayout({
    controllerRequest: { text: 'request', observedAt: at(0), actorId: 'engineer', source: 'factory-cockpit', namespaceId: 'ns-1' },
    steps: [step({ id: 'pending', status: 'pending', startedAt: undefined, durationMs: undefined })],
  }, { now: Date.parse(at(999)) })
  const request = layout.lanes.find((lane) => lane.id === 'engineer').blocks[0]
  assert.equal(request.durationMs, 0)
  assert.ok(request.widthPct >= 3)
  assert.equal(layout.bounds.running, false)
})

test('pending steps are queued as dashed blocks', () => {
  const layout = buildWaterfallLayout([
    step({ id: 'done', startedAt: at(0), durationMs: 10000 }),
    step({ id: 'queued', status: 'pending', name: 'waiting' }),
  ])
  const queued = layout.lanes.find((lane) => lane.id === 'code').blocks.find((block) => block.id === 'queued')
  assert.equal(queued.pending, true)
  assert.ok(renderWaterfall(layout).includes('is-queued'))
})

// --------------------------------------------------------- missing metrics "-"

function sampleProjection() {
  return {
    workflowId: 'test-run-1',
    workflowType: 'feature-session',
    title: 'Test run',
    status: 'completed',
    steps: [
      step({
        id: 'engineer-1',
        name: 'kickoff',
        lane: 'human',
        responsibility: { kind: 'human', name: 'engineer' },
        startedAt: at(0),
        durationMs: 5000,
      }),
      step({
        id: 'agent-1',
        name: 'analyse',
        lane: 'agent',
        responsibility: { kind: 'agent', name: 'analyst' },
        startedAt: at(5),
        durationMs: 25000,
      }),
      step({ id: 'code-1', name: 'build', lane: 'code', startedAt: at(30), durationMs: 15000 }),
    ],
  }
}

test('the run-strip renders "-" for every metric the projection omits', () => {
  const layout = buildWaterfallLayout(sampleProjection(), { now: Date.parse(at(45)) })
  const html = renderRunStrip(layout.strip)
  for (const key of ['cost', 'tokens', 'read', 'written']) {
    assert.ok(html.includes(`data-stat="${key}"`), `missing stat chip ${key}`)
  }
  assert.ok(html.includes(`COST</span><span class="stat-val mono">${DASH}</span>`))
  assert.ok(html.includes(`TOKENS</span><span class="stat-val mono">${DASH}</span>`))
  assert.ok(html.includes(`READ</span><span class="stat-val mono">${DASH}</span>`))
  assert.ok(html.includes(`WRITTEN</span><span class="stat-val mono">${DASH}</span>`))
  // RUNTIME is derivable, so it is NOT a dash.
  assert.ok(!html.includes(`RUNTIME</span><span class="stat-val mono">${DASH}</span>`))
})

test('the runtime runs from the first start to the last completion', () => {
  const layout = buildWaterfallLayout(sampleProjection(), { now: Date.parse(at(999)) })
  const runtime = layout.strip.stats.find((stat) => stat.key === 'runtime')
  assert.equal(runtime.value, '45.0s')
})

test('agent lanes render "-" for model and context', () => {
  const layout = buildWaterfallLayout(sampleProjection())
  const html = renderWaterfall(layout)
  assert.ok(html.includes('lane-kind-agent'), 'expected an agent lane')
  assert.ok(html.includes('<span class="submeta-key">Model</span><span class="submeta-val mono">-</span>'))
  assert.ok(html.includes('<span class="submeta-key">Context</span>'))
  assert.ok(html.includes('<span class="submeta-val mono">-</span>'))
})

test('every rendered block carries the status glyph', () => {
  const layout = buildWaterfallLayout([
    step({ id: 'done', status: 'completed', startedAt: at(0), durationMs: 1000 }),
    step({ id: 'bad', status: 'failed', startedAt: at(1), durationMs: 1000 }),
    step({ id: 'live', status: 'running', startedAt: at(2), durationMs: 1000 }),
    step({ id: 'wait', status: 'pending' }),
  ])
  const states = layout.lanes.flatMap((lane) => lane.blocks).map((block) => block.state)
  assert.ok(states.includes('completed'))
  assert.ok(states.includes('failed'))
  assert.ok(states.includes('active'))
  assert.ok(states.includes('pending'))
  const html = renderWaterfall(layout)
  assert.ok(html.includes('\u2713'))
  assert.ok(html.includes('\u2717'))
  assert.ok(html.includes('\u25CF'))
  assert.ok(html.includes('\u25CB'))
})

test('renderWaterfallTimeline composes the strip and the waterfall', () => {
  const layout = buildWaterfallLayout(sampleProjection(), { now: Date.parse(at(45)) })
  const html = renderWaterfallTimeline(layout)
  assert.ok(html.includes('data-run-strip="true"'))
  assert.ok(html.includes('data-waterfall="true"'))
  // Click selection contract: blocks keep a data-step-id for event delegation.
  assert.ok(html.includes('data-step-id="agent-1"'))
})

// ----------------------------------------------------------------- axis ticks

test('axis labels are humanized and ticks span 0..100%', () => {
  assert.equal(axisLabel(0), '0s')
  assert.equal(axisLabel(30000), '30s')
  assert.equal(axisLabel(60000), '1m')
  assert.equal(axisLabel(150000), '2.5m')

  const ticks = buildTicks(120000)
  assert.equal(ticks[0].pct, 0)
  assert.equal(ticks[ticks.length - 1].pct, 100)
})
