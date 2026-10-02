import {
  classifyActorKind,
  classifyLane,
  mapProjectionToLanes,
  mapProjectionToRunSummary,
  mapProjectionToSessionDetail,
  mapStepsToPhaseSegments,
  mapWorkflowStateToRunStatus,
} from './mappers'

const startedAt = '2026-09-30T16:00:00.000Z'

function isoOffset(seconds: number): string {
  return new Date(Date.parse(startedAt) + seconds * 1000).toISOString()
}

const projection = {
  schemaVersion: '2',
  title: 'Deliver the AgentOS adapter',
  status: 'running',
  steps: [
    {
      id: 'request',
      name: 'request',
      status: 'completed',
      lane: 'human',
      responsibility: { kind: 'human', name: 'benjamin' },
      startedAt,
      completedAt: isoOffset(10),
      durationMs: 10_000,
    },
    {
      id: 'plan',
      name: 'plan',
      status: 'completed',
      responsibility: { kind: 'agent', name: 'planner' },
      startedAt: isoOffset(10),
      completedAt: isoOffset(60),
      durationMs: 50_000,
      ticks: [12, isoOffset(30)],
    },
    {
      id: 'build',
      name: 'build',
      status: 'running',
      responsibility: { kind: 'code', name: 'builder' },
      startedAt: isoOffset(60),
      durationMs: 120_000,
      contextPct: 42,
    },
  ],
}

const snapshot = {
  workflowId: 'wf-1',
  namespaceId: 'ns-1',
  revision: 5,
  relations: { rootWorkflowId: 'wf-1', ticket: 'ABC-1' },
  controllerExecution: { kind: 'agentos', caseId: 'case-9' },
  projection,
}

describe('mappers', () => {
  describe('classification', () => {
    it('prefers the explicit lane then the responsibility kind', () => {
      expect(classifyActorKind({ lane: 'code', responsibility: { kind: 'agent' } })).toBe('code')
      expect(classifyActorKind({ responsibility: { kind: 'human' } })).toBe('human')
    })

    it('falls back to name hints and defaults to agent', () => {
      expect(classifyActorKind({ id: 'run-oracle' })).toBe('code')
      expect(classifyActorKind({ name: 'human approval' })).toBe('human')
      expect(classifyActorKind({ id: 'mystery' })).toBe('agent')
    })

    it('groups human/code into single lanes and agents by name', () => {
      expect(classifyLane({ responsibility: { kind: 'human' } })).toEqual({
        id: 'engineer',
        kind: 'human',
        label: 'engineer',
      })
      expect(classifyLane({ responsibility: { kind: 'code' } })).toEqual({ id: 'code', kind: 'code', label: 'code' })
      expect(classifyLane({ responsibility: { kind: 'agent', name: 'planner' } })).toEqual({
        id: 'agent:planner',
        kind: 'agent',
        label: 'planner',
      })
    })
  })

  describe('mapWorkflowStateToRunStatus', () => {
    it.each([
      ['running', 'running'],
      ['waiting_human', 'running'],
      ['completed', 'succeeded'],
      ['failed', 'failed'],
      ['cancelled', 'failed'],
      ['pending', 'queued'],
    ])('maps %s to %s', (state, expected) => {
      expect(mapWorkflowStateToRunStatus(state)).toBe(expected)
    })

    it('derives the status from the steps when the state is a lifecycle value', () => {
      expect(mapWorkflowStateToRunStatus('existing', projection.steps)).toBe('running')
      expect(mapWorkflowStateToRunStatus(undefined, [{ status: 'completed' }])).toBe('succeeded')
      expect(mapWorkflowStateToRunStatus(undefined, [{ status: 'failed' }])).toBe('failed')
      expect(mapWorkflowStateToRunStatus(undefined, [])).toBe('queued')
    })
  })

  describe('mapStepsToPhaseSegments', () => {
    it('computes ratios from the step durations and assigns tones/status', () => {
      const segments = mapStepsToPhaseSegments(projection.steps, 180)
      expect(segments.map((segment) => segment.key)).toEqual(['request', 'plan', 'build'])
      expect(segments[0]?.tone).toBe('amber')
      expect(segments[1]?.tone).toBe('violet')
      expect(segments[2]?.tone).toBe('cyan')
      expect(segments[0]?.ratio).toBeCloseTo(10 / 180)
      expect(segments[2]?.status).toBe('running')
    })

    it('falls back to an even split when no duration is known', () => {
      const segments = mapStepsToPhaseSegments([{ id: 'a' }, { id: 'b' }], 0)
      expect(segments[0]?.ratio).toBeCloseTo(0.5)
    })
  })

  describe('mapProjectionToRunSummary', () => {
    it('maps the snapshot fields into a RunSummary', () => {
      const run = mapProjectionToRunSummary(snapshot)
      expect(run.id).toBe('wf-1')
      expect(run.workflow).toBe('Deliver the AgentOS adapter')
      expect(run.status).toBe('running')
      expect(run.currentPhase).toBe('build')
      expect(run.goal).toBe('Deliver the AgentOS adapter')
      expect(run.durationSec).toBe(180)
      expect(run.phases.map((phase) => phase.key)).toEqual(['request', 'plan', 'build'])
    })
  })

  describe('mapProjectionToLanes', () => {
    it('derives one lane per actor with relative blocks', () => {
      const lanes = mapProjectionToLanes(snapshot)

      // Lanes are ordered: engineer (human), code (workspace), then agents.
      expect(lanes.map((lane) => lane.id)).toEqual(['engineer', 'code', 'agent:planner'])

      const human = lanes[0]
      expect(human?.kind).toBe('human')
      expect(human?.request?.label).toBe('request')
      expect(human?.blocks).toHaveLength(0)

      const code = lanes[1]
      expect(code?.kind).toBe('workspace')
      expect(code?.blocks[0]).toMatchObject({ label: 'build', startSec: 60, status: 'running' })

      const planner = lanes[2]
      expect(planner?.kind).toBe('agent')
      expect(planner?.blocks[0]).toMatchObject({ label: 'plan', startSec: 10, status: 'done' })
      expect(planner?.blocks[0]?.ticksSec).toEqual([12, 30])
    })

    it('groups a code step into the workspace lane', () => {
      const lanes = mapProjectionToLanes({
        projection: { steps: [{ id: 'build', name: 'build', status: 'running', responsibility: { kind: 'code' } }] },
      })
      expect(lanes[0]).toMatchObject({ id: 'code', kind: 'workspace', tone: 'cyan' })
      expect(lanes[0]?.blocks[0]?.endSec).toBeGreaterThanOrEqual(2)
    })
  })

  describe('mapProjectionToSessionDetail', () => {
    it('builds a complete session from projection + enrichment payloads', () => {
      const session = mapProjectionToSessionDetail(
        snapshot,
        { startedAt, workflowId: 'wf-1' },
        {
          items: [
            {
              kind: 'tool',
              createdAt: startedAt,
              facts: { tool: 'read', text: 'plan.md', durationMs: 500, tokensRead: 1200, tokensWritten: 30 },
            },
          ],
        },
        { tokens: 1234 }
      )

      expect(session.id).toBe('wf-1')
      expect(session.sandbox).toBe('ABC-1')
      expect(session.status).toBe('running')
      expect(session.startedAt).toBe(startedAt)
      expect(session.tokens).toBe(1234)
      expect(session.tokensRead).toBe(1200)
      expect(session.tokensWritten).toBe(30)
      expect(session.steps.map((step) => step.key)).toEqual(['request', 'plan', 'build'])
      expect(session.events[0]).toMatchObject({ type: 'tool_call', tool: 'read', durationSec: 0.5 })
      expect(session.phase.kind).toBe('code')
      expect(session.nowSec).toBeGreaterThan(0)
    })

    it('degrades gracefully on an empty/malformed snapshot', () => {
      const session = mapProjectionToSessionDetail(undefined)
      expect(session.id).toBe('unknown')
      expect(session.lanes).toEqual([])
      expect(session.events).toEqual([])
    })
  })
})
