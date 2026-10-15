import {
  classifyActorKind,
  classifyLane,
  extractAllowedActions,
  extractAttempts,
  extractBlockers,
  extractInteractions,
  extractRealCost,
  filterEventsForSelection,
  mapProjectionToFactoryRun,
  mapProjectionToLanes,
  mapProjectionToPhaseByStepId,
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

const controllerRequest = {
  text: 'Ship the feature safely',
  namespaceId: 'ns-1',
  observedAt: startedAt,
  actorId: 'benjamin',
  source: 'factory-cockpit',
}

/** Snapshot whose steps loop does NOT already derive a `request` block. */
const snapshotWithControllerRequest = {
  ...snapshot,
  controllerRequest,
  projection: {
    ...projection,
    steps: projection.steps.filter((step) => step.id !== 'request'),
  },
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

    it('carries a readable label only when name differs from id', () => {
      const segments = mapStepsToPhaseSegments(
        [
          { id: 'request', name: 'request' }, // same → no label
          { id: 'plan', name: 'Plan the work' }, // differs → label set
          { id: 'build' }, // no name → no label
        ],
        0
      )
      expect(segments[0]?.label).toBeUndefined()
      expect(segments[1]?.label).toBe('Plan the work')
      expect(segments[2]?.label).toBeUndefined()
    })
  })

  describe('mapProjectionToRunSummary', () => {
    it('maps the snapshot fields into a RunSummary', () => {
      const run = mapProjectionToRunSummary(snapshot)
      expect(run.id).toBe('wf-1')
      expect(run.workflow).toBe('Deliver the AgentOS adapter')
      expect(run.id).toBe('wf-1')
      expect(run.status).toBe('running')
      expect(run.currentPhase).toBe('build')
      expect(run.goal).toBe('Deliver the AgentOS adapter')
      expect(run.durationSec).toBe(180)
      expect(run.phases.map((phase) => phase.key)).toEqual(['request', 'plan', 'build'])
      expect(run.unknownCostCount).toBe(0)
    })

    it('uses workflow id as the display fallback for legacy records without title', () => {
      const run = mapProjectionToRunSummary({
        workflowId: 'legacy-id',
        projection: { workflowType: 'delivery', status: 'pending', steps: [] },
      })
      expect(run.workflow).toBe('legacy-id')
      expect(run.id).toBe('legacy-id')
    })

    it('maps the real cost from the metrics payload', () => {
      const run = mapProjectionToRunSummary(snapshot, {
        realCost: {
          cost: 1.0723,
          unknownCostCount: 0,
          liveTokens: 150,
          paused: false,
          active: true,
          runCostThreshold: null,
        },
      })
      expect(run.costUsd).toBe(1.0723)
      expect(run.unknownCostCount).toBe(0)
    })

    it('preserves a partially unknown cost (unknownCostCount > 0)', () => {
      const run = mapProjectionToRunSummary(snapshot, {
        realCost: { cost: 0.42, unknownCostCount: 3, liveTokens: 0 },
      })
      expect(run.costUsd).toBe(0.42)
      expect(run.unknownCostCount).toBe(3)
    })

    it('falls back to the projection cost when metrics carry no realCost', () => {
      const run = mapProjectionToRunSummary(
        { ...snapshot, projection: { ...projection, costUsd: 9.99 } },
        { tokens: 10 }
      )
      expect(run.costUsd).toBe(9.99)
      expect(run.unknownCostCount).toBe(0)
    })

    it('injects a synthetic Demande utilisateur segment first when controllerRequest is present and no request step exists', () => {
      const run = mapProjectionToRunSummary(snapshotWithControllerRequest)
      expect(run.phases[0]?.key).toBe('user-request')
      expect(run.phases[0]?.label).toBe('Demande utilisateur')
      expect(run.phases[0]?.tone).toBe('amber')
      // At least one step has startedAt → status should be 'done'
      expect(run.phases[0]?.status).toBe('done')
      // Original steps follow in order
      expect(run.phases.slice(1).map((p) => p.key)).toEqual(['plan', 'build'])
    })

    it('does not inject a Demande utilisateur segment when a request step already exists in phases', () => {
      // snapshot already contains a step with id='request'
      const run = mapProjectionToRunSummary({ ...snapshot, controllerRequest })
      expect(run.phases.map((p) => p.key)).toEqual(['request', 'plan', 'build'])
      expect(run.phases.some((p) => p.key === 'user-request')).toBe(false)
    })

    it('does not inject a Demande utilisateur segment when there is no controllerRequest', () => {
      const run = mapProjectionToRunSummary(snapshot)
      expect(run.phases.map((p) => p.key)).toEqual(['request', 'plan', 'build'])
    })
  })

  describe('extractRealCost', () => {
    it('extracts a nested realCost block', () => {
      expect(extractRealCost({ realCost: { cost: 1, unknownCostCount: 2, liveTokens: 3 } })).toEqual(
        expect.objectContaining({ cost: 1, unknownCostCount: 2, liveTokens: 3 })
      )
    })

    it('falls back to a flattened metrics payload', () => {
      expect(extractRealCost({ cost: 5, unknownCostCount: 1 })).toEqual(
        expect.objectContaining({ cost: 5, unknownCostCount: 1 })
      )
    })

    it('returns undefined without any usable cost field', () => {
      expect(extractRealCost({ tokens: 10 })).toBeUndefined()
      expect(extractRealCost(undefined)).toBeUndefined()
      expect(extractRealCost(null)).toBeUndefined()
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
      expect(planner?.blocks[0]).toMatchObject({ label: 'plan', startSec: 10, status: 'completed' })
      expect(planner?.blocks[0]?.ticksSec).toEqual([12, 30])
    })

    it('keeps planned actor lanes empty until execution starts while preserving failed executions', () => {
      const lanes = mapProjectionToLanes({
        projection: {
          steps: [
            {
              id: 'tony',
              name: 'Tony_Starck',
              status: 'failed',
              startedAt,
              completedAt: isoOffset(10),
              responsibility: { kind: 'agent', name: 'Tony_Starck' },
            },
            {
              id: 'thor',
              name: 'Thor',
              status: 'pending',
              dependsOn: ['tony'],
              responsibility: { kind: 'agent', name: 'Thor' },
            },
            {
              id: 'thor-ready',
              name: 'Thor',
              status: 'ready',
              dependsOn: ['tony'],
              responsibility: { kind: 'agent', name: 'Thor' },
            },
            {
              id: 'tony-retry',
              name: 'Tony retry',
              status: 'running',
              startedAt: isoOffset(12),
              responsibility: { kind: 'agent', name: 'Tony_Starck' },
            },
          ],
        },
      })

      expect(lanes.flatMap((lane) => lane.blocks).map((block) => [block.label, block.status])).toEqual([
        ['Tony_Starck', 'failed'],
        ['Tony retry', 'running'],
      ])
      expect(lanes.find((lane) => lane.label === 'Thor')).toMatchObject({ blocks: [] })
    })

    it('only shows waiting_human after execution has begun', () => {
      const lanes = mapProjectionToLanes({
        projection: {
          steps: [
            { id: 'not-started', status: 'waiting_human', responsibility: { kind: 'agent', name: 'Thor' } },
            {
              id: 'started',
              status: 'waiting_human',
              startedAt,
              responsibility: { kind: 'agent', name: 'Tony_Starck' },
            },
          ],
        },
      })

      expect(lanes.flatMap((lane) => lane.blocks)).toEqual([
        expect.objectContaining({ label: 'started', status: 'waiting_human' }),
      ])
    })

    it('keeps a waiting-human agent attempt visible with its suspended status', () => {
      const lanes = mapProjectionToLanes(
        {
          projection: {
            steps: [
              {
                id: 'technical-design',
                name: 'Architect',
                status: 'waiting_human',
                responsibility: { kind: 'agent', name: 'Architect' },
              },
            ],
          },
        },
        { startedAt },
        [
          {
            attemptId: 'attempt-1',
            stepId: 'technical-design',
            attemptNumber: 1,
            agentName: 'Architect',
            status: 'waiting_human',
            caseId: 'case-1',
            startedAt,
          },
        ]
      )

      expect(lanes[0]?.blocks).toEqual([
        expect.objectContaining({ label: 'Architect', stepId: 'technical-design', status: 'waiting_human' }),
      ])
    })

    it('renders each agent attempt as a distinct ordered timeline block', () => {
      const lanes = mapProjectionToLanes(
        {
          projection: {
            steps: [
              {
                id: 'plan',
                name: 'Plan',
                status: 'running',
                responsibility: { kind: 'agent', name: 'planner' },
              },
            ],
          },
        },
        { startedAt },
        [
          {
            attemptId: 'attempt-2',
            stepId: 'plan',
            attemptNumber: 2,
            agentName: 'planner',
            status: 'running',
            caseId: 'case-2',
            startedAt: isoOffset(20),
          },
          {
            attemptId: 'attempt-1',
            stepId: 'plan',
            attemptNumber: 1,
            agentName: 'planner',
            status: 'failed',
            caseId: 'case-1',
            startedAt: isoOffset(5),
            completedAt: isoOffset(15),
          },
        ]
      )

      expect(lanes).toHaveLength(1)
      expect(lanes[0]?.blocks).toEqual([
        expect.objectContaining({
          label: 'Plan \u00b7 attempt 1',
          stepId: 'plan',
          startSec: 0,
          endSec: 10,
          status: 'failed',
        }),
        expect.objectContaining({ label: 'Plan \u00b7 attempt 2', stepId: 'plan', startSec: 15, status: 'running' }),
      ])
    })

    it('propagates attempt metadata (agentName, attemptId, attemptNumber, caseId, failureCode) onto timeline blocks', () => {
      const lanes = mapProjectionToLanes(
        {
          projection: {
            steps: [
              {
                id: 'build',
                name: 'Build',
                status: 'failed',
                responsibility: { kind: 'agent', name: 'builder' },
              },
            ],
          },
        },
        { startedAt },
        [
          {
            attemptId: 'a-1',
            stepId: 'build',
            attemptNumber: 1,
            agentName: 'builder',
            status: 'failed',
            caseId: 'case-1',
            failureCode: 'VERIFICATION_FAILED',
            startedAt: isoOffset(0),
            completedAt: isoOffset(30),
          },
        ]
      )

      const block = lanes[0]?.blocks[0]
      expect(block).toMatchObject({
        stepId: 'build',
        agentName: 'builder',
        attemptId: 'a-1',
        attemptNumber: 1,
        caseId: 'case-1',
        failureCode: 'VERIFICATION_FAILED',
        status: 'failed',
      })
    })

    it('uses terminal timestamps in finishedAt, completedAt, endedAt priority order', () => {
      const lanes = mapProjectionToLanes({
        projection: {
          steps: [
            {
              id: 'finished',
              status: 'completed',
              startedAt,
              finishedAt: isoOffset(40),
              completedAt: isoOffset(30),
              endedAt: isoOffset(20),
              durationMs: 10_000,
              responsibility: { kind: 'code' },
            },
            {
              id: 'completed',
              status: 'completed',
              startedAt: isoOffset(50),
              completedAt: isoOffset(80),
              endedAt: isoOffset(70),
              responsibility: { kind: 'code' },
            },
            {
              id: 'ended',
              status: 'completed',
              startedAt: isoOffset(90),
              endedAt: isoOffset(110),
              responsibility: { kind: 'code' },
            },
          ],
        },
      })
      expect(lanes[0]?.blocks.map((block) => block.endSec - block.startSec)).toEqual([40, 30, 20])
    })

    it('falls back to durationMs for invalid or reversed absolute bounds', () => {
      const lanes = mapProjectionToLanes({
        projection: {
          steps: [
            {
              id: 'invalid',
              status: 'completed',
              startedAt,
              completedAt: 'invalid',
              durationMs: 7_000,
              responsibility: { kind: 'code' },
            },
            {
              id: 'reversed',
              status: 'completed',
              startedAt: isoOffset(20),
              completedAt: isoOffset(10),
              durationMs: 9_000,
              responsibility: { kind: 'code' },
            },
          ],
        },
      })
      expect(lanes[0]?.blocks.map((block) => block.endSec - block.startSec)).toEqual([7, 9])
    })

    it('reconstructs running wall time but keeps waiting-human frozen', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(100)))
      try {
        const lanes = mapProjectionToLanes({
          projection: {
            steps: [
              { id: 'running', status: 'running', startedAt, responsibility: { kind: 'code' } },
              {
                id: 'waiting',
                status: 'waiting_human',
                startedAt: isoOffset(20),
                durationMs: 5_000,
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
            ],
          },
        })
        expect(lanes.find((lane) => lane.id === 'code')?.blocks[0]?.endSec).toBe(100)
        expect(lanes.find((lane) => lane.id === 'agent:reviewer')?.blocks[0]?.endSec).toBe(25)
      } finally {
        jest.useRealTimers()
      }
    })

    it('groups a code step into the workspace lane', () => {
      const lanes = mapProjectionToLanes({
        projection: { steps: [{ id: 'build', name: 'build', status: 'running', responsibility: { kind: 'code' } }] },
      })
      expect(lanes[0]).toMatchObject({ id: 'code', kind: 'workspace', tone: 'cyan' })
      expect(lanes[0]?.blocks[0]?.endSec).toBeGreaterThanOrEqual(2)
    })

    it('surfaces the persisted controllerRequest as the engineer lane request block', () => {
      const lanes = mapProjectionToLanes(snapshotWithControllerRequest)

      const engineer = lanes.find((candidate) => candidate.id === 'engineer')
      expect(engineer).toMatchObject({ kind: 'human', label: 'engineer', tone: 'amber', subtitle: 'benjamin' })
      expect(engineer?.request).toMatchObject({ label: 'request', startSec: 0, status: 'completed' })
      expect(engineer?.request?.description).toBe('Ship the feature safely')
      expect(engineer?.request?.endSec).toBeGreaterThanOrEqual(2)
      expect(engineer?.blocks).toHaveLength(0)
    })

    it('accepts a legacy string controllerRequest', () => {
      const lanes = mapProjectionToLanes({
        ...snapshot,
        controllerRequest: 'Do the thing',
        projection: { ...projection, steps: projection.steps.filter((step) => step.id !== 'request') },
      })
      const engineer = lanes.find((candidate) => candidate.id === 'engineer')
      expect(engineer?.request?.description).toBe('Do the thing')
    })

    it('does not duplicate the engineer lane/request when a request step already provides it', () => {
      const lanes = mapProjectionToLanes({ ...snapshot, controllerRequest })

      const engineers = lanes.filter((candidate) => candidate.id === 'engineer')
      expect(engineers).toHaveLength(1)
      // The step-derived request block wins: the controllerRequest text is not
      // injected as a second block.
      expect(engineers[0]?.request?.description).toBeUndefined()
      expect(engineers[0]?.blocks).toHaveLength(0)
    })
  })

  describe('extractInteractions', () => {
    it('maps a bare array of records defensively', () => {
      const result = extractInteractions([
        {
          interactionId: 'i-1',
          stepId: 'build',
          interactionType: 'approval',
          status: 'waiting',
          createdAt: startedAt,
          payload: {
            prompt: 'Approve the deploy?',
            actions: [{ id: 'approve', label: 'Approve' }, { id: 'reject' }],
            recipient: 'benjamin',
          },
        },
      ])

      expect(result).toEqual([
        {
          interactionId: 'i-1',
          stepId: 'build',
          interactionType: 'approval',
          status: 'waiting',
          prompt: 'Approve the deploy?',
          actions: [
            { id: 'approve', label: 'Approve' },
            { id: 'reject', label: 'reject' },
          ],
          recipient: 'benjamin',
          createdAt: startedAt,
        },
      ])
    })

    it('accepts { items: [...] } and { data: [...] } wrappers', () => {
      expect(extractInteractions({ items: [{ interactionId: 'a' }] }).map((i) => i.interactionId)).toEqual(['a'])
      expect(extractInteractions({ data: [{ interactionId: 'b' }] }).map((i) => i.interactionId)).toEqual(['b'])
    })

    it('degrades gracefully on empty and malformed payloads', () => {
      expect(extractInteractions(undefined)).toEqual([])
      expect(extractInteractions(null)).toEqual([])
      expect(extractInteractions({ foo: 'bar' })).toEqual([])
      expect(extractInteractions('nope')).toEqual([])

      const [fallback] = extractInteractions([{}])
      expect(fallback).toMatchObject({
        interactionId: 'interaction-1',
        stepId: '',
        interactionType: 'unknown',
        status: 'unknown',
      })
    })
  })

  describe('extractAttempts', () => {
    it('maps a bare array of records defensively', () => {
      const result = extractAttempts([
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'builder',
          status: 'failed',
          caseId: 'case-1',
          failureCode: 'TEST_FAILED',
          resultEvidenceId: 'ev-1',
          revision: 3,
          createdAt: startedAt,
          startedAt,
          completedAt: isoOffset(10),
        },
      ])

      expect(result).toEqual([
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'builder',
          status: 'failed',
          caseId: 'case-1',
          failureCode: 'TEST_FAILED',
          resultEvidenceId: 'ev-1',
          revision: 3,
          createdAt: startedAt,
          startedAt,
          completedAt: isoOffset(10),
        },
      ])
    })

    it('accepts { items: [...] } and { data: [...] } wrappers', () => {
      expect(extractAttempts({ items: [{ attemptId: 'a' }] }).map((a) => a.attemptId)).toEqual(['a'])
      expect(extractAttempts({ data: [{ attemptId: 'b' }] }).map((a) => a.attemptId)).toEqual(['b'])
    })

    it('degrades gracefully on empty and malformed payloads', () => {
      expect(extractAttempts(undefined)).toEqual([])
      expect(extractAttempts(null)).toEqual([])
      expect(extractAttempts({ foo: 'bar' })).toEqual([])
      expect(extractAttempts('nope')).toEqual([])

      const [fallback] = extractAttempts([{}])
      expect(fallback).toMatchObject({
        attemptId: 'attempt-1',
        stepId: '',
        attemptNumber: 0,
        agentName: '',
        status: 'unknown',
        caseId: '',
      })
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
      expect(session.executionState).toBe('running')
      expect(session.activelyRunning).toBe(true)
      expect(session.startedAt).toBe(startedAt)
      expect(session.tokens).toBe(1234)
      expect(session.tokensRead).toBe(1200)
      expect(session.tokensWritten).toBe(30)
      expect(session.steps.map((step) => step.key)).toEqual(['request', 'plan', 'build'])
      expect(session.events[0]).toMatchObject({ type: 'tool_call', tool: 'read', durationSec: 0.5 })
      expect(session.phase.kind).toBe('code')
      expect(session.nowSec).toBeGreaterThan(0)
      expect(session.timelineOriginAt).toBe(startedAt)
    })

    it('freezes terminal session nowSec on the persisted end instead of wall time', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(1_000)))
      try {
        const session = mapProjectionToSessionDetail({
          workflowId: 'done',
          projection: {
            status: 'completed',
            steps: [
              {
                id: 'done',
                status: 'completed',
                startedAt,
                completedAt: isoOffset(25),
                responsibility: { kind: 'code' },
              },
            ],
          },
        })
        expect(session.nowSec).toBe(25)
      } finally {
        jest.useRealTimers()
      }
    })

    it('marks waiting-human separately from the coarse running run status', () => {
      const session = mapProjectionToSessionDetail(
        {
          ...snapshot,
          projection: {
            ...projection,
            status: 'waiting_human',
            steps: projection.steps.map((step) => (step.id === 'build' ? { ...step, status: 'waiting_human' } : step)),
          },
        },
        undefined,
        undefined,
        undefined,
        undefined,
        [
          {
            attemptId: 'a-1',
            stepId: 'build',
            attemptNumber: 1,
            agentName: 'Architect',
            status: 'waiting_human',
            caseId: 'case-1',
            startedAt,
          },
        ]
      )

      expect(session.status).toBe('running')
      expect(session.executionState).toBe('waiting_human')
      expect(session.activelyRunning).toBe(false)
      expect(session.steps.find((step) => step.key === 'build')?.status).toBe('waiting_human')
      expect(session.phase.stepStatus).toBe('waiting_human')
    })

    it('degrades gracefully on an empty/malformed snapshot', () => {
      const session = mapProjectionToSessionDetail(undefined)
      expect(session.id).toBe('unknown')
      expect(session.lanes).toEqual([])
      expect(session.events).toEqual([])
      expect(session.costUsd).toBe(0)
      expect(session.unknownCostCount).toBe(0)
    })

    it('maps the real cost and live tokens from metrics', () => {
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, {
        realCost: {
          cost: 1.0723,
          unknownCostCount: 0,
          liveTokens: 150,
          paused: false,
          active: true,
          runCostThreshold: 10,
        },
      })
      expect(session.costUsd).toBe(1.0723)
      expect(session.unknownCostCount).toBe(0)
      expect(session.tokens).toBe(150)
    })

    it('keeps a partially unknown cost as a stored lower bound', () => {
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, {
        realCost: { cost: 0, unknownCostCount: 4, liveTokens: 0 },
      })
      expect(session.costUsd).toBe(0)
      expect(session.unknownCostCount).toBe(4)
    })

    it('falls back to the projection cost and metrics tokens without realCost', () => {
      const session = mapProjectionToSessionDetail(
        { ...snapshot, projection: { ...projection, costUsd: 2.5 } },
        undefined,
        undefined,
        { tokens: 99 }
      )
      expect(session.costUsd).toBe(2.5)
      expect(session.unknownCostCount).toBe(0)
      expect(session.tokens).toBe(99)
    })

    it('surfaces read-only interactions in the session (list, Gates count and events)', () => {
      const interactions = [
        {
          interactionId: 'i-1',
          stepId: 'build',
          interactionType: 'approval',
          status: 'waiting',
          createdAt: startedAt,
          payload: { prompt: 'Approve the deploy?' },
        },
      ]
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, interactions)

      expect(session.interactions).toHaveLength(1)
      expect(session.interactions?.[0]?.interactionId).toBe('i-1')
      const gates = session.phase.sections.find((section) => section.label === 'Gates')
      expect(gates?.count).toBe(1)

      const interactionEvents = session.events.filter((event) => event.text.includes('Approve the deploy?'))
      expect(interactionEvents).toHaveLength(1)
      expect(interactionEvents[0]).toMatchObject({ time: '16:00:00', type: 'agent_message' })
      // The interaction event is additive: the projection step events survive.
      expect(session.events.some((event) => event.type === 'phase_start')).toBe(true)
    })

    it('reports zero gates when no interaction is available', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      const gates = session.phase.sections.find((section) => section.label === 'Gates')
      expect(gates?.count).toBe(0)
      expect(session.interactions).toEqual([])
    })

    it('renders the real gate items of the active step only', () => {
      const interactions = [
        {
          interactionId: 'i-1',
          stepId: 'build',
          interactionType: 'approval',
          status: 'waiting',
          createdAt: startedAt,
          payload: { prompt: 'Approve the deploy?', actions: [{ id: 'approve', label: 'Approve' }] },
        },
        { interactionId: 'i-2', stepId: 'plan', interactionType: 'notice', status: 'done' },
      ]
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, interactions)

      const gates = session.phase.sections.find((section) => section.label === 'Gates')
      expect(gates?.count).toBe(1)
      expect(gates?.body).toBeUndefined()
      expect(gates?.items).toEqual([
        {
          title: 'approval',
          status: 'waiting',
          subtitle: 'Approve the deploy?',
          actions: [{ id: 'approve', label: 'Approve' }],
        },
      ])
    })

    it('renders the real evidence of the active step and of the current attempt result', () => {
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'builder',
          status: 'running',
          caseId: 'case-1',
          resultEvidenceId: 'ev-2',
        },
      ]
      const evidence = {
        items: [
          {
            evidenceId: 'ev-1',
            stepId: 'build',
            kind: 'oracle',
            outcome: 'pass',
            facts: { message: 'all tests pass' },
            createdAt: startedAt,
          },
          { evidenceId: 'ev-2', stepId: 'plan', kind: 'result', outcome: 'done', facts: { summary: 'result summary' } },
          { evidenceId: 'ev-3', stepId: 'plan', kind: 'noise' },
        ],
      }
      const session = mapProjectionToSessionDetail(snapshot, undefined, evidence, undefined, undefined, attempts)

      const outputs = session.phase.sections.find((section) => section.label === 'Sorties')
      expect(outputs?.count).toBe(2)
      expect(outputs?.body).toBeUndefined()
      expect(outputs?.items?.[0]).toMatchObject({ title: 'oracle', status: 'pass', subtitle: 'all tests pass' })
      expect(outputs?.items?.[1]).toMatchObject({ title: 'result', subtitle: 'result summary' })
    })

    it('reports an explicit empty message for Gates and Sorties when nothing is known', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      const gates = session.phase.sections.find((section) => section.label === 'Gates')
      const outputs = session.phase.sections.find((section) => section.label === 'Sorties')
      expect(gates?.body).toBe("Aucune gate d'interaction pour cette phase.")
      expect(outputs?.body).toBe('Aucune sortie enregistrée pour cette phase.')
    })

    it('exposes the real agent configuration and honestly marks prompts/model unavailable', () => {
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'builder',
          status: 'running',
          caseId: 'case-7',
        },
      ]
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, attempts)

      const config = session.phase.sections.find((section) => section.label === "Configuration de l'agent")
      expect(config?.items).toEqual(
        expect.arrayContaining([
          { title: 'Agent', subtitle: 'builder' },
          { title: 'Case', subtitle: 'case-7' },
          { title: 'Tentatives', subtitle: '2/1' },
        ])
      )

      const prompts = session.phase.sections.find((section) => section.label === 'Prompts compilés')
      expect(prompts).toMatchObject({ notAvailable: true, body: 'Non disponible (nécessite exposition backend)' })
      expect(prompts?.count).toBeUndefined()

      const model = session.phase.sections.find((section) => section.label === 'Modèle LLM résolu')
      expect(model).toMatchObject({ notAvailable: true, body: 'Non disponible (nécessite exposition backend)' })
    })

    it('surfaces the real attempts of the active step and a dynamic attempt string', () => {
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'builder',
          status: 'failed',
          caseId: 'case-1',
          failureCode: 'TEST_FAILED',
        },
        {
          attemptId: 'a-2',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'builder',
          status: 'running',
          caseId: 'case-2',
        },
        // Another step's attempt must be ignored for the active phase detail.
        { attemptId: 'a-3', stepId: 'plan', attemptNumber: 1, agentName: 'planner', status: 'completed', caseId: 'c' },
      ]

      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, attempts)

      expect(session.attempts).toHaveLength(3)
      expect(session.phase.attempt).toBe('2/2')
      expect(session.phase.currentAttemptNumber).toBe(2)
      expect(session.phase.totalAttempts).toBe(2)
      expect(session.phase.attempts?.map((a) => a.attemptId)).toEqual(['a-1', 'a-2'])
      expect(session.phase.agentName).toBe('builder')
      expect(session.phase.attemptStatus).toBe('running')
      expect(session.phase.caseId).toBe('case-2')
    })

    it('never emits an impossible empty attempt string when there is no attempt', () => {
      const noAttempts = mapProjectionToSessionDetail(snapshot)
      expect(noAttempts.phase.attempt).toBe('1/1')
      expect(noAttempts.phase.attempts).toBeUndefined()

      const emptyAttempts = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, [])
      expect(emptyAttempts.phase.attempt).toBe('1/1')
      expect(emptyAttempts.attempts).toEqual([])
    })

    it('exposes the failure code of the current attempt when it failed', () => {
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'builder',
          status: 'failed',
          caseId: 'case-1',
          failureCode: 'TEST_FAILED',
        },
      ])
      expect(session.phase.attempt).toBe('1/1')
      expect(session.phase.failureCode).toBe('TEST_FAILED')
      expect(session.phase.attemptStatus).toBe('failed')
    })

    it('maps governed allowedActions and blockers into the session', () => {
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, undefined, {
        allowedActions: [
          { type: 'reply', interactionId: 'i-1', stepId: 'build', expectedRevision: 3, label: 'Approve?' },
          { type: 'retry', stepId: 'build', expectedRevision: 5 },
        ],
        blockers: [{ code: 'STEP_BLOCKED', stepId: 'build', message: 'Step build blocked' }],
      })

      expect(session.allowedActions).toEqual([
        { type: 'reply', interactionId: 'i-1', stepId: 'build', expectedRevision: 3, label: 'Approve?' },
        { type: 'retry', stepId: 'build', expectedRevision: 5 },
      ])
      expect(session.blockers).toEqual([
        { code: 'STEP_BLOCKED', stepId: 'build', label: 'Step build blocked', message: 'Step build blocked' },
      ])
    })

    it('degrades to empty action/blocker lists without an actions payload', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      expect(session.allowedActions).toEqual([])
      expect(session.blockers).toEqual([])
    })
  })

  describe('extractAllowedActions', () => {
    it('maps a bare array defensively and copies identity fields verbatim', () => {
      expect(
        extractAllowedActions([
          {
            type: 'cancel_attempt',
            attemptId: 'a-1',
            stepId: 'build',
            caseId: 'c-1',
            expectedRevision: 2,
            label: 'Cancel',
          },
          { type: 'reply', interactionId: 'i-1', questionEventId: 'q-1', expectedRevision: 3 },
        ])
      ).toEqual([
        {
          type: 'cancel_attempt',
          attemptId: 'a-1',
          stepId: 'build',
          caseId: 'c-1',
          expectedRevision: 2,
          label: 'Cancel',
        },
        { type: 'reply', interactionId: 'i-1', questionEventId: 'q-1', expectedRevision: 3 },
      ])
    })

    it('accepts the { allowedActions } block and drops entries without a type', () => {
      expect(extractAllowedActions({ allowedActions: [{ type: 'retry', stepId: 'x' }, { stepId: 'y' }] })).toEqual([
        { type: 'retry', stepId: 'x' },
      ])
    })

    it('degrades gracefully on empty/malformed payloads', () => {
      expect(extractAllowedActions(undefined)).toEqual([])
      expect(extractAllowedActions(null)).toEqual([])
      expect(extractAllowedActions({ foo: 'bar' })).toEqual([])
      expect(extractAllowedActions('nope')).toEqual([])
    })
  })

  describe('extractBlockers', () => {
    it('maps the backend message to label and keeps code/stepId', () => {
      expect(
        extractBlockers([{ code: 'WAITING_HUMAN_INTERACTION', stepId: 'build', message: 'Waiting for human' }])
      ).toEqual([
        {
          code: 'WAITING_HUMAN_INTERACTION',
          stepId: 'build',
          label: 'Waiting for human',
          message: 'Waiting for human',
        },
      ])
    })

    it('accepts the { blockers } block and falls back to the code as label', () => {
      expect(extractBlockers({ blockers: [{ code: 'UNKNOWN_RUNTIME' }] })).toEqual([
        { code: 'UNKNOWN_RUNTIME', label: 'UNKNOWN_RUNTIME' },
      ])
    })

    it('degrades gracefully on empty/malformed payloads', () => {
      expect(extractBlockers(undefined)).toEqual([])
      expect(extractBlockers(null)).toEqual([])
      expect(extractBlockers({ foo: 'bar' })).toEqual([])
    })
  })

  describe('mapProjectionToPhaseByStepId', () => {
    it('builds a PhaseDetail for every known step id', () => {
      const steps = projection.steps
      const map = mapProjectionToPhaseByStepId(steps, 'running')
      expect([...map.keys()].sort()).toEqual(['build', 'plan', 'request'].sort())
    })

    it('returns the correct detail for a specific step', () => {
      const steps = projection.steps
      const map = mapProjectionToPhaseByStepId(steps, 'running')
      const buildDetail = map.get('build')
      expect(buildDetail?.name).toBe('build')
      expect(buildDetail?.stepId).toBe('build')
      expect(buildDetail?.stepStatus).toBe('running')
    })

    it('populates attempt data for the correct step only', () => {
      const steps = projection.steps
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'builder',
          status: 'running',
          caseId: 'case-1',
        },
      ]
      const map = mapProjectionToPhaseByStepId(steps, 'running', [], attempts)
      const buildDetail = map.get('build')
      expect(buildDetail?.agentName).toBe('builder')
      expect(buildDetail?.caseId).toBe('case-1')
      // plan step should have no attempt data
      const planDetail = map.get('plan')
      expect(planDetail?.caseId).toBeUndefined()
    })

    it('returns an empty map for an empty steps list', () => {
      const map = mapProjectionToPhaseByStepId([], 'queued')
      expect(map.size).toBe(0)
    })
  })

  describe('mapProjectionToSessionDetail - phaseByStepId', () => {
    it('exposes a phaseByStepId map with an entry for each step', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      expect(session.phaseByStepId).toBeDefined()
      expect([...(session.phaseByStepId?.keys() ?? [])].sort()).toEqual(['build', 'plan', 'request'].sort())
    })

    it('phaseByStepId entries reflect the correct step detail independently of the active phase', () => {
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'plan',
          attemptNumber: 1,
          agentName: 'planner',
          status: 'completed',
          caseId: 'case-plan',
        },
        {
          attemptId: 'a-2',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'builder',
          status: 'running',
          caseId: 'case-build',
        },
      ]
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, attempts)

      // The active phase (build) is selected by default
      expect(session.phase.stepId).toBe('build')
      expect(session.phase.caseId).toBe('case-build')

      // But phaseByStepId gives us the plan detail with its own attempt
      const planPhase = session.phaseByStepId?.get('plan')
      expect(planPhase?.stepId).toBe('plan')
      expect(planPhase?.caseId).toBe('case-plan')
      expect(planPhase?.agentName).toBe('planner')
    })
  })

  describe('filterEventsForSelection', () => {
    const startedAt = '2026-09-30T16:00:00.000Z'

    const evidenceItems = [
      {
        evidenceId: 'ev-plan-1',
        stepId: 'plan',
        kind: 'tool',
        createdAt: startedAt,
        facts: { tool: 'read', text: 'plan.md' },
      },
      {
        evidenceId: 'ev-build-1',
        stepId: 'build',
        kind: 'tool',
        createdAt: startedAt,
        facts: { tool: 'bash', text: 'npm test' },
      },
      {
        evidenceId: 'ev-build-2',
        stepId: 'build',
        kind: 'tool',
        createdAt: startedAt,
        facts: { tool: 'write', text: 'output.txt' },
      },
    ]

    const interactions = [
      { interactionId: 'i-1', stepId: 'build', interactionType: 'approval', status: 'waiting', createdAt: startedAt },
      { interactionId: 'i-2', stepId: 'plan', interactionType: 'notice', status: 'done', createdAt: startedAt },
    ]

    const attempts = [
      {
        attemptId: 'a-1',
        stepId: 'build',
        attemptNumber: 1,
        agentName: 'builder',
        status: 'failed',
        caseId: 'case-1',
        resultEvidenceId: 'ev-build-1',
      },
      {
        attemptId: 'a-2',
        stepId: 'build',
        attemptNumber: 2,
        agentName: 'builder',
        status: 'running',
        caseId: 'case-2',
        resultEvidenceId: 'ev-build-2',
      },
    ]

    it('returns all events when no stepId is provided (global view)', () => {
      const allEvents = [
        { time: '16:00:00', type: 'tool_call' as const, text: 'plan.md' },
        { time: '16:00:01', type: 'tool_call' as const, text: 'npm test' },
      ]
      const result = filterEventsForSelection(allEvents, evidenceItems, [], [], undefined, undefined)
      expect(result).toBe(allEvents)
    })

    it('filters events to only the selected step (step-scoped, no attemptId)', () => {
      const result = filterEventsForSelection([], evidenceItems, interactions, attempts, 'build', undefined)
      // Should include evidence from build step + interactions for build step
      const texts = result.map((e) => e.text)
      expect(texts).toContain('npm test')
      expect(texts).toContain('output.txt')
      // plan evidence must be excluded
      expect(texts).not.toContain('plan.md')
      // build interaction must be included
      expect(result.some((e) => e.text.includes('approval'))).toBe(true)
      // plan interaction must be excluded
      expect(result.some((e) => e.text.includes('notice'))).toBe(false)
    })

    it('filters events to the specific attempt via resultEvidenceId (attempt-scoped)', () => {
      const result = filterEventsForSelection([], evidenceItems, interactions, attempts, 'build', 'a-1')
      const texts = result.map((e) => e.text)
      // Only ev-build-1 is the resultEvidenceId of attempt a-1
      expect(texts).toContain('npm test')
      expect(texts).not.toContain('output.txt') // ev-build-2 belongs to a-2
      expect(texts).not.toContain('plan.md')
    })

    it('returns only the second attempt evidence when a-2 is selected', () => {
      const result = filterEventsForSelection([], evidenceItems, interactions, attempts, 'build', 'a-2')
      const texts = result.map((e) => e.text)
      expect(texts).toContain('output.txt') // ev-build-2 belongs to a-2
      expect(texts).not.toContain('npm test') // ev-build-1 belongs to a-1
    })

    it('returns no evidence events but still includes step interactions when the attempt has no correlated evidence', () => {
      // Interactions are step-scoped (not attempt-scoped): they are always relevant
      // for the selected step regardless of which specific attempt is targeted.
      const attemptsNoEvidence = [
        { attemptId: 'a-99', stepId: 'build', attemptNumber: 1, agentName: 'x', status: 'running', caseId: 'c' },
      ]
      const result = filterEventsForSelection([], evidenceItems, interactions, attemptsNoEvidence, 'build', 'a-99')
      // No evidence events (the attempt has no resultEvidenceId)
      expect(result.some((e) => e.type === 'tool_call')).toBe(false)
      // But the step's interaction event IS included (gates are step-scoped)
      expect(result.some((e) => e.text.includes('approval'))).toBe(true)
    })

    it('two agents on the same step: each attempt returns only its own evidence', () => {
      const twoAgentEvidenceItems = [
        {
          evidenceId: 'ev-a1',
          stepId: 'build',
          kind: 'tool',
          createdAt: startedAt,
          facts: { tool: 'read', text: 'agent-1-output' },
        },
        {
          evidenceId: 'ev-a2',
          stepId: 'build',
          kind: 'tool',
          createdAt: startedAt,
          facts: { tool: 'write', text: 'agent-2-output' },
        },
      ]
      const twoAgentAttempts = [
        {
          attemptId: 'a-agent1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'agent-1',
          status: 'failed',
          caseId: 'case-a1',
          resultEvidenceId: 'ev-a1',
        },
        {
          attemptId: 'a-agent2',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'agent-2',
          status: 'running',
          caseId: 'case-a2',
          resultEvidenceId: 'ev-a2',
        },
      ]

      const agent1Events = filterEventsForSelection(
        [],
        twoAgentEvidenceItems,
        [],
        twoAgentAttempts,
        'build',
        'a-agent1'
      )
      expect(agent1Events.map((e) => e.text)).toEqual(['agent-1-output'])

      const agent2Events = filterEventsForSelection(
        [],
        twoAgentEvidenceItems,
        [],
        twoAgentAttempts,
        'build',
        'a-agent2'
      )
      expect(agent2Events.map((e) => e.text)).toEqual(['agent-2-output'])
    })

    it('out-of-order attempt evidence: selecting a-1 never returns a-2 events even if a-2 arrived first', () => {
      // Simulates the scenario where SSE brings a-2 data before a-1 data
      const outOfOrderEvidence = [
        { evidenceId: 'ev-a2', stepId: 'build', kind: 'tool', createdAt: startedAt, facts: { text: 'a2-event' } },
        { evidenceId: 'ev-a1', stepId: 'build', kind: 'tool', createdAt: startedAt, facts: { text: 'a1-event' } },
      ]
      const outOfOrderAttempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'x',
          status: 'failed',
          caseId: 'c1',
          resultEvidenceId: 'ev-a1',
        },
        {
          attemptId: 'a-2',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'x',
          status: 'running',
          caseId: 'c2',
          resultEvidenceId: 'ev-a2',
        },
      ]

      const a1Events = filterEventsForSelection([], outOfOrderEvidence, [], outOfOrderAttempts, 'build', 'a-1')
      expect(a1Events.map((e) => e.text)).toEqual(['a1-event'])

      const a2Events = filterEventsForSelection([], outOfOrderEvidence, [], outOfOrderAttempts, 'build', 'a-2')
      expect(a2Events.map((e) => e.text)).toEqual(['a2-event'])
    })
  })

  describe('mapProjectionToSessionDetail - eventsByStepId / eventsByAttemptId', () => {
    const startedAt = '2026-09-30T16:00:00.000Z'
    const baseSnapshot = {
      workflowId: 'wf-ev',
      namespaceId: 'ns-1',
      projection: {
        schemaVersion: '2',
        title: 'Event filter test',
        status: 'running',
        steps: [
          { id: 'plan', name: 'plan', status: 'completed', responsibility: { kind: 'agent', name: 'planner' } },
          { id: 'build', name: 'build', status: 'running', responsibility: { kind: 'agent', name: 'builder' } },
        ],
      },
    }

    const evidence = {
      items: [
        { evidenceId: 'ev-plan', stepId: 'plan', kind: 'tool', createdAt: startedAt, facts: { text: 'plan-output' } },
        {
          evidenceId: 'ev-build-1',
          stepId: 'build',
          kind: 'tool',
          createdAt: startedAt,
          facts: { text: 'build-a1-output' },
        },
        {
          evidenceId: 'ev-build-2',
          stepId: 'build',
          kind: 'tool',
          createdAt: startedAt,
          facts: { text: 'build-a2-output' },
        },
      ],
    }

    const attempts = [
      {
        attemptId: 'a-1',
        stepId: 'build',
        attemptNumber: 1,
        agentName: 'builder',
        status: 'failed',
        caseId: 'case-1',
        resultEvidenceId: 'ev-build-1',
      },
      {
        attemptId: 'a-2',
        stepId: 'build',
        attemptNumber: 2,
        agentName: 'builder',
        status: 'running',
        caseId: 'case-2',
        resultEvidenceId: 'ev-build-2',
      },
    ]

    it('populates eventsByStepId for each step', () => {
      const session = mapProjectionToSessionDetail(baseSnapshot, undefined, evidence, undefined, undefined, attempts)
      expect(session.eventsByStepId).toBeDefined()
      const planEvents = session.eventsByStepId?.get('plan') ?? []
      expect(planEvents.map((e) => e.text)).toContain('plan-output')
      const buildEvents = session.eventsByStepId?.get('build') ?? []
      // Both attempts' evidence should appear in the step-scoped view
      expect(buildEvents.map((e) => e.text)).toContain('build-a1-output')
      expect(buildEvents.map((e) => e.text)).toContain('build-a2-output')
    })

    it('populates eventsByAttemptId keyed by attemptId', () => {
      const session = mapProjectionToSessionDetail(baseSnapshot, undefined, evidence, undefined, undefined, attempts)
      expect(session.eventsByAttemptId).toBeDefined()

      const a1Events = session.eventsByAttemptId?.get('a-1') ?? []
      expect(a1Events.map((e) => e.text)).toContain('build-a1-output')
      expect(a1Events.map((e) => e.text)).not.toContain('build-a2-output')

      const a2Events = session.eventsByAttemptId?.get('a-2') ?? []
      expect(a2Events.map((e) => e.text)).toContain('build-a2-output')
      expect(a2Events.map((e) => e.text)).not.toContain('build-a1-output')
    })

    it('eventsByAttemptId returns only interaction events (no evidence events) for an attempt with no correlated evidence', () => {
      // No evidence payload → no evidence items to correlate.
      // Interactions are absent too in this scenario.
      const noEvidenceAttempts = [
        { attemptId: 'a-orphan', stepId: 'build', attemptNumber: 1, agentName: 'x', status: 'running', caseId: 'c' },
      ]
      const session = mapProjectionToSessionDetail(
        baseSnapshot,
        undefined,
        undefined,
        undefined,
        undefined,
        noEvidenceAttempts
      )
      // The key must exist (attempt is known)
      expect(session.eventsByAttemptId?.has('a-orphan')).toBe(true)
      // No evidence and no interactions for this step → empty array
      expect(session.eventsByAttemptId?.get('a-orphan')).toEqual([])
    })
  })

  describe('mapProjectionToFactoryRun', () => {
    const snapshot = {
      workflowId: 'wf-1',
      namespaceId: 'ns-1',
      relations: { rootWorkflowId: 'wf-1', ticket: 'ABC-1', branch: 'feature/x' },
      projection: {
        schemaVersion: '2',
        title: 'My run',
        status: 'running',
        workflowType: 'adw_simple_sdlc',
        steps: [],
      },
    }

    it('maps the run identity and carries the namespaceId', () => {
      const run = mapProjectionToFactoryRun(snapshot)
      expect(run.id).toBe('wf-1')
      expect(run.title).toBe('My run')
      expect(run.namespaceId).toBe('ns-1')
      expect(run.project).toBe('ns-1')
      expect(run.ticket).toBe('ABC-1')
      expect(run.branch).toBe('feature/x')
      expect(run.workflowType).toBe('adw_simple_sdlc')
      expect(run.status).toBe('working')
    })

    it('leaves namespaceId undefined when the snapshot has none', () => {
      const run = mapProjectionToFactoryRun({ workflowId: 'wf-2', projection: { title: 'No ns', steps: [] } })
      expect(run.namespaceId).toBeUndefined()
    })

    it('forces the status for removed snapshots', () => {
      expect(mapProjectionToFactoryRun(snapshot, 'destroyed').status).toBe('destroyed')
    })
  })
})
