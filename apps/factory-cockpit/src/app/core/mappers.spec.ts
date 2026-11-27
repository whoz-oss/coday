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
  mapProjectionToPhaseByAttemptId,
  mapProjectionToRunSummary,
  mapProjectionToSessionDetail,
  mapSessionDetailToRunSummary,
  mapStepsToPhaseSegments,
  mapWorkflowStateToRunStatus,
  resolveMainCaseId,
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
    it('preserves skipped branches in phase segments and run summaries without marking them done', () => {
      const steps = [
        { id: 'selected', status: 'completed' },
        { id: 'unselected', name: 'Not selected branch', status: 'skipped', executed: false },
      ]
      expect(mapStepsToPhaseSegments(steps).map((segment) => segment.status)).toEqual(['done', 'skipped'])
      const summary = mapProjectionToRunSummary({ workflowId: 'routed', projection: { steps } })
      expect(summary.status).toBe('succeeded')
      expect(summary.phases[1]).toMatchObject({ key: 'unselected', label: 'Not selected branch', status: 'skipped' })
    })

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
      // Active step is `build`, whose responsibility name is `builder`.
      expect(run.currentPhase).toBe('builder')
      expect(run.goal).toBe('Deliver the AgentOS adapter')
      expect(run.durationSec).toBe(180)
      expect(run.phases.map((phase) => phase.key)).toEqual(['request', 'plan', 'build'])
      expect(run.unknownCostCount).toBeNull()
    })

    it('uses the actor name, not a long step name, as currentPhase', () => {
      const longName =
        'Archay checks the worktree, moves the ticket to In Progress, reads the issue and prepares the plan'
      const run = mapProjectionToRunSummary({
        workflowId: 'wf-long',
        projection: {
          status: 'running',
          steps: [
            {
              id: 'kickoff',
              name: longName,
              status: 'running',
              responsibility: { kind: 'agent', name: 'Archay' },
            },
          ],
        },
      })
      expect(run.currentPhase).toBe('Archay')
    })

    it('resolves currentPhase from agentName, then facts.agentName, then falls back to step name/id', () => {
      const phase = (step: Record<string, unknown>) =>
        mapProjectionToRunSummary({ workflowId: 'w', projection: { status: 'running', steps: [step] } }).currentPhase
      expect(phase({ id: 's', name: 'Long name', status: 'running', agentName: 'Thor' })).toBe('Thor')
      expect(phase({ id: 's', name: 'Long name', status: 'running', facts: { agentName: 'Loki' } })).toBe('Loki')
      expect(phase({ id: 's', name: 'Long name', status: 'running' })).toBe('Long name')
      expect(phase({ id: 's', status: 'running' })).toBe('s')
    })

    it('uses the actor name of the last step when no step is active', () => {
      const run = mapProjectionToRunSummary({
        workflowId: 'wf-done',
        projection: {
          status: 'completed',
          steps: [
            { id: 'a', name: 'First', status: 'completed', responsibility: { kind: 'agent', name: 'Planner' } },
            {
              id: 'b',
              name: 'Last long sentence',
              status: 'completed',
              responsibility: { kind: 'agent', name: 'Archay' },
            },
          ],
        },
      })
      expect(run.currentPhase).toBe('Archay')
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

    it('keeps cost unavailable when metrics carry no realCost', () => {
      const run = mapProjectionToRunSummary(
        { ...snapshot, projection: { ...projection, costUsd: 9.99 } },
        { tokens: 10 }
      )
      expect(run.costUsd).toBeNull()
      expect(run.unknownCostCount).toBeNull()
    })

    it('injects a synthetic user request segment first when controllerRequest is present and no request step exists', () => {
      const run = mapProjectionToRunSummary(snapshotWithControllerRequest)
      expect(run.phases[0]?.key).toBe('user-request')
      expect(run.phases[0]?.label).toBe('User request')
      expect(run.phases[0]?.tone).toBe('amber')
      // At least one step has startedAt → status should be 'done'
      expect(run.phases[0]?.status).toBe('done')
      // Original steps follow in order
      expect(run.phases.slice(1).map((p) => p.key)).toEqual(['plan', 'build'])
    })

    it('does not inject a user request segment when a request step already exists in phases', () => {
      // snapshot already contains a step with id='request'
      const run = mapProjectionToRunSummary({ ...snapshot, controllerRequest })
      expect(run.phases.map((p) => p.key)).toEqual(['request', 'plan', 'build'])
      expect(run.phases.some((p) => p.key === 'user-request')).toBe(false)
    })

    it('does not inject a user request segment when there is no controllerRequest', () => {
      const run = mapProjectionToRunSummary(snapshot)
      expect(run.phases.map((p) => p.key)).toEqual(['request', 'plan', 'build'])
    })
  })

  describe('extractRealCost', () => {
    it('extracts nested cost and optional run-wide token counters', () => {
      expect(
        extractRealCost({
          realCost: { cost: 1, unknownCostCount: 2, liveTokens: 3, totalTokens: 0, inputTokens: 0, outputTokens: 0 },
        })
      ).toEqual(
        expect.objectContaining({
          cost: 1,
          unknownCostCount: 2,
          liveTokens: 3,
          totalTokens: 0,
          inputTokens: 0,
          outputTokens: 0,
        })
      )
    })

    it('preserves explicit unavailable analytics as nulls', () => {
      expect(
        extractRealCost({
          realCost: {
            cost: null,
            unknownCostCount: null,
            totalTokens: null,
            inputTokens: null,
            outputTokens: null,
            cacheReadTokens: null,
            cacheWriteTokens: null,
            liveTokens: null,
          },
        })
      ).toEqual(expect.objectContaining({ cost: null, unknownCostCount: null }))
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

    it('renders AGENT_NEEDS_RESEARCH as a human-wait block instead of a failed block', () => {
      const lanes = mapProjectionToLanes(
        {
          projection: {
            steps: [
              {
                id: 'research',
                name: 'Research',
                status: 'failed',
                responsibility: { kind: 'agent', name: 'researcher' },
              },
            ],
          },
        },
        { startedAt },
        [
          {
            attemptId: 'attempt-research',
            stepId: 'research',
            attemptNumber: 1,
            agentName: 'researcher',
            status: 'failed',
            caseId: 'case-research',
            failureCode: 'AGENT_NEEDS_RESEARCH',
            startedAt,
            completedAt: isoOffset(30),
          },
        ]
      )

      expect(lanes[0]?.blocks[0]).toMatchObject({
        stepId: 'research',
        failureCode: 'AGENT_NEEDS_RESEARCH',
        status: 'waiting_human',
      })
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

    it('keeps a NEEDS_RESEARCH predecessor frozen and exposes its pending successor without invented time', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(680)))
      try {
        const input = {
          projection: {
            steps: [
              {
                id: 'technical-design',
                name: 'Architect Frontend technical design',
                status: 'running',
                responsibility: { kind: 'agent', name: 'Architect' },
              },
            ],
          },
        }
        const first = {
          attemptId: 'attempt-1',
          stepId: 'technical-design',
          attemptNumber: 1,
          agentName: 'Architect',
          status: 'indeterminate',
          caseId: 'shared-case',
          failureCode: 'AGENT_NEEDS_RESEARCH',
          startedAt,
          completedAt: isoOffset(545),
          activeDurationMs: 125_000,
          waitingDurationMs: 420_000,
        }
        const pending = {
          attemptId: 'attempt-2',
          stepId: 'technical-design',
          attemptNumber: 2,
          agentName: 'Architect',
          status: 'pending',
          caseId: 'shared-case',
          activeDurationMs: 0,
          waitingDurationMs: 0,
        }

        const pendingLanes = mapProjectionToLanes(input, { startedAt }, [first, pending])
        expect(pendingLanes[0]?.blocks).toEqual([
          expect.objectContaining({ attemptId: 'attempt-1', status: 'waiting_human', startSec: 0, endSec: 545 }),
          expect.objectContaining({ attemptId: 'attempt-2', status: 'pending', startSec: 545, endSec: 545 }),
        ])

        const runningLanes = mapProjectionToLanes(input, { startedAt }, [
          first,
          { ...pending, status: 'running', startedAt: isoOffset(600), activeSince: isoOffset(670) },
        ])
        expect(runningLanes[0]?.blocks[0]).toMatchObject({ attemptId: 'attempt-1', endSec: 545 })
        expect(runningLanes[0]?.blocks[1]).toMatchObject({
          attemptId: 'attempt-2',
          status: 'running',
          startSec: 600,
          endSec: 680,
          activeDurationSec: 10,
        })
      } finally {
        jest.useRealTimers()
      }
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

    it('reconstructs running wall time but never counts waiting-human wall time as active execution', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(1_240)))
      try {
        const input = {
          projection: {
            steps: [
              { id: 'running', status: 'running', startedAt, responsibility: { kind: 'code' } },
              {
                id: 'waiting',
                status: 'waiting_human',
                startedAt,
                durationMs: 40_000,
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
            ],
          },
        }
        const lanes = mapProjectionToLanes(input)
        expect(lanes.find((lane) => lane.id === 'code')?.blocks[0]?.endSec).toBe(1_240)
        expect(lanes.find((lane) => lane.id === 'agent:reviewer')?.blocks[0]?.endSec).toBe(40)

        // A reload of the exact same durable snapshot must reconstruct the same
        // frozen active duration; no mapper-local cache is involved.
        expect(mapProjectionToLanes(input)).toEqual(lanes)
      } finally {
        jest.useRealTimers()
      }
    })

    it('does not invent elapsed wall time when an open interval has no valid start timestamp', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(100)))
      try {
        const lanes = mapProjectionToLanes({
          projection: {
            steps: [
              {
                id: 'waiting',
                status: 'waiting_human',
                durationMs: 3_000,
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
            ],
          },
        })
        expect(lanes[0]?.blocks).toHaveLength(0)
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
      expect(engineer?.request).toMatchObject({
        label: 'launch request',
        kind: 'launch_request',
        startSec: 0,
        status: 'completed',
      })
      expect(engineer?.request?.description).toBe('Ship the feature safely')
      expect(engineer?.request?.endSec).toBeGreaterThanOrEqual(2)
      expect(engineer?.blocks).toHaveLength(0)
    })

    it('creates a neutral launch request from run creation when initialRequest is absent', () => {
      const lanes = mapProjectionToLanes({
        workflowId: 'wf-created',
        createdAt: startedAt,
        projection: {
          status: 'running',
          steps: [
            {
              id: 'plan',
              status: 'running',
              startedAt: isoOffset(10),
              responsibility: { kind: 'agent', name: 'planner' },
            },
          ],
        },
      })

      const engineer = lanes.find((candidate) => candidate.id === 'engineer')
      expect(engineer).toMatchObject({ subtitle: 'human' })
      expect(engineer?.request).toMatchObject({
        label: 'launch request',
        kind: 'launch_request',
        startSec: 0,
        endSec: 10,
        status: 'completed',
      })
      expect(engineer?.request?.description).toBeUndefined()
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
          activeDurationMs: 10_000,
          activeSince: isoOffset(10),
          waitingDurationMs: 20_000,
          waitingSince: isoOffset(30),
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
          activeDurationMs: 10_000,
          activeSince: isoOffset(10),
          waitingDurationMs: 20_000,
          waitingSince: isoOffset(30),
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
    it('keeps inactive routed branches skipped and selectable without falsely showing execution or success', () => {
      const steps = [
        {
          id: 'architect',
          name: 'Scope',
          status: 'completed',
          responsibility: { kind: 'agent', name: 'Architect' },
          decision: {
            mode: 'multiple',
            routes: [
              { name: 'frontend', target: 'front' },
              { name: 'backend', target: 'back' },
            ],
          },
          routeDecision: { attemptId: 'a', resultId: 'r', selectedRoutes: ['frontend'] },
        },
        {
          id: 'front',
          name: 'Frontend',
          status: 'completed',
          activation: 'selected',
          executed: true,
          responsibility: { kind: 'agent', name: 'Frontend' },
        },
        {
          id: 'back',
          name: 'Backend',
          status: 'skipped',
          activation: 'not_selected',
          executed: false,
          responsibility: { kind: 'agent', name: 'Backend' },
        },
      ]
      const session = mapProjectionToSessionDetail({ workflowId: 'routed', projection: { status: 'completed', steps } })
      expect(session.status).toBe('succeeded')
      expect(session.routingSteps).toHaveLength(3)
      expect(session.steps.find((step) => step.key === 'back')?.status).toBe('skipped')
      expect(session.lanes.flatMap((lane) => lane.blocks).some((block) => block.stepId === 'back')).toBe(false)
      expect(session.phaseByStepId?.get('back')?.stepStatus).toBe('skipped')
      const routing = session.phaseByStepId
        ?.get('architect')
        ?.sections.find((section) => section.label === 'Conditional routing')
      expect(routing?.items).toContainEqual({ title: 'backend', subtitle: 'back', status: 'not_selected' })
    })
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
      expect(session.tokens).toBeNull()
      expect(session.tokensRead).toBeNull()
      expect(session.tokensWritten).toBeNull()
      expect(session.steps.map((step) => step.key)).toEqual(['request', 'plan', 'build'])
      expect(session.events[0]).toMatchObject({ type: 'tool_call', tool: 'read', durationSec: 0.5 })
      expect(session.phase.kind).toBe('code')
      expect(session.nowSec).toBeGreaterThan(0)
      expect(session.timelineOriginAt).toBe(startedAt)
    })

    it('builds a dedicated launch-request detail with the real run metadata', () => {
      const session = mapProjectionToSessionDetail({
        ...snapshotWithControllerRequest,
        createdAt: isoOffset(-5),
        projection: { ...snapshotWithControllerRequest.projection, workflowType: 'delivery' },
      })

      expect(session.launchRequest).toMatchObject({
        name: 'Deliver the AgentOS adapter',
        kind: 'launch request',
        owner: 'benjamin',
        stepStatus: 'completed',
      })
      expect(session.launchRequest?.caseId).toBeUndefined()
      expect(session.launchRequest?.sections).toEqual([
        {
          label: 'Launch request',
          items: expect.arrayContaining([
            { title: 'Type', subtitle: 'launch request' },
            { title: 'Run', subtitle: 'Deliver the AgentOS adapter' },
            { title: 'Created', subtitle: isoOffset(-5) },
            { title: 'Namespace', subtitle: 'ns-1' },
            { title: 'Workflow', subtitle: 'delivery' },
            { title: 'Author', subtitle: 'benjamin' },
          ]),
        },
        { label: 'Initial comment', body: 'Ship the feature safely' },
      ])
    })

    it('builds launch-request detail without an initial comment or author', () => {
      const session = mapProjectionToSessionDetail({
        workflowId: 'wf-created',
        namespaceId: 'ns-created',
        createdAt: startedAt,
        projection: { title: 'Created run', workflowType: 'delivery', status: 'running', steps: [] },
      })

      expect(session.launchRequest).toMatchObject({
        name: 'Created run',
        kind: 'launch request',
        owner: 'Not recorded',
      })
      expect(session.launchRequest?.sections).toEqual([
        {
          label: 'Launch request',
          items: expect.arrayContaining([
            { title: 'Created', subtitle: startedAt },
            { title: 'Namespace', subtitle: 'ns-created' },
            { title: 'Workflow', subtitle: 'delivery' },
            { title: 'Author', subtitle: 'Not recorded' },
          ]),
        },
        { label: 'Initial comment', body: 'No initial comment recorded.' },
      ])
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

    it('uses authoritative attempt counters across waiting and resume with unchanged historical startedAt', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(1_250)))
      try {
        const baseAttempt = {
          attemptId: 'same-attempt',
          stepId: 'review',
          attemptNumber: 1,
          agentName: 'reviewer',
          caseId: 'case-review',
          startedAt,
          activeDurationMs: 40_000,
          waitingDurationMs: 1_200_000,
        }
        const workflow = {
          workflowId: 'authoritative-duration',
          projection: {
            status: 'running',
            steps: [
              {
                id: 'review',
                status: 'running',
                startedAt,
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
            ],
          },
        }
        const attempts = [{ ...baseAttempt, status: 'running', activeSince: isoOffset(1_240) }]
        const session = mapProjectionToSessionDetail(workflow, undefined, undefined, undefined, undefined, attempts)
        const refreshed = mapProjectionToSessionDetail(workflow, undefined, undefined, undefined, undefined, attempts)
        const block = session.lanes[0]?.blocks[0]

        expect(session.durationSec).toBe(50)
        expect(refreshed.durationSec).toBe(50)
        expect(block).toMatchObject({ activeDurationSec: 50, waitingDurationSec: 1_200 })
        expect(block?.endSec).toBe(1_250)
      } finally {
        jest.useRealTimers()
      }
    })

    it('keeps authoritative active duration zero instead of falling back to wall time', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(1_240)))
      try {
        const session = mapProjectionToSessionDetail(
          {
            workflowId: 'zero-duration',
            projection: {
              status: 'waiting_human',
              steps: [
                {
                  id: 'review',
                  status: 'waiting_human',
                  startedAt,
                  durationMs: 40_000,
                  responsibility: { kind: 'agent', name: 'reviewer' },
                },
              ],
            },
          },
          undefined,
          undefined,
          undefined,
          undefined,
          [
            {
              attemptId: 'same-attempt',
              stepId: 'review',
              attemptNumber: 1,
              agentName: 'reviewer',
              status: 'waiting_human',
              caseId: 'case-review',
              startedAt,
              activeDurationMs: 0,
              waitingDurationMs: 0,
              waitingSince: isoOffset(40),
            },
          ]
        )

        expect(session.durationSec).toBe(0)
        expect(session.lanes[0]?.blocks[0]).toMatchObject({ activeDurationSec: 0, waitingDurationSec: 1_200 })
      } finally {
        jest.useRealTimers()
      }
    })

    it('keeps active duration frozen while waiting-human instead of counting wall time', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(1_240)))
      try {
        const waitingSnapshot = {
          workflowId: 'waiting-duration',
          projection: {
            status: 'waiting_human',
            steps: [
              {
                id: 'review',
                status: 'waiting_human',
                startedAt,
                durationMs: 40_000,
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
            ],
          },
        }
        const firstLoad = mapProjectionToSessionDetail(waitingSnapshot)
        const refreshed = mapProjectionToSessionDetail(waitingSnapshot)

        expect(firstLoad).toMatchObject({
          activelyRunning: false,
          executionState: 'waiting_human',
          durationSec: 40,
          nowSec: 40,
        })
        expect(refreshed.durationSec).toBe(firstLoad.durationSec)
        expect(refreshed.nowSec).toBe(firstLoad.nowSec)
      } finally {
        jest.useRealTimers()
      }
    })

    it('accumulates a durable resumed interval without adding the preceding human wait', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(1_250)))
      try {
        const session = mapProjectionToSessionDetail({
          workflowId: 'resumed-duration',
          projection: {
            status: 'running',
            steps: [
              {
                id: 'before-wait',
                status: 'completed',
                startedAt,
                completedAt: isoOffset(40),
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
              {
                id: 'after-wait',
                status: 'running',
                startedAt: isoOffset(1_240),
                responsibility: { kind: 'agent', name: 'reviewer' },
              },
            ],
          },
        })

        expect(session.durationSec).toBe(50)
        expect(session.activelyRunning).toBe(true)
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
      expect(session.costUsd).toBeNull()
      expect(session.unknownCostCount).toBeNull()
    })

    it('maps run-wide real-cost counters and never presents liveTokens as cumulative usage', () => {
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, {
        realCost: {
          cost: 1.0723,
          unknownCostCount: 0,
          liveTokens: 150,
          totalTokens: 1000,
          inputTokens: 600,
          outputTokens: 400,
          cacheReadTokens: 50,
          cacheWriteTokens: 5,
          paused: false,
          active: true,
          runCostThreshold: 10,
        },
      })
      expect(session.costUsd).toBe(1.0723)
      expect(session.unknownCostCount).toBe(0)
      expect(session.tokens).toBe(1000)
      expect(session.tokensRead).toBe(600)
      expect(session.tokensWritten).toBe(400)
      expect(session.cacheReadTokens).toBe(50)
      expect(session.cacheWriteTokens).toBe(5)
    })

    it('keeps a partially unknown cost as a stored lower bound', () => {
      const session = mapProjectionToSessionDetail(snapshot, undefined, undefined, {
        realCost: { cost: 0, unknownCostCount: 4, liveTokens: 0 },
      })
      expect(session.costUsd).toBe(0)
      expect(session.unknownCostCount).toBe(4)
    })

    it('does not fall back to projection or budget cost when analytics is explicitly unavailable', () => {
      const session = mapProjectionToSessionDetail(
        { ...snapshot, costUsd: 99, projection: { ...projection, costUsd: 88 } },
        undefined,
        undefined,
        { realCost: { cost: null, unknownCostCount: null, totalTokens: null, inputTokens: null, outputTokens: null } }
      )
      expect(session.costUsd).toBeNull()
      expect(session.unknownCostCount).toBeNull()
      expect(session.tokens).toBeNull()
    })

    it('keeps tokens unknown when run-wide counters are absent', () => {
      const session = mapProjectionToSessionDetail(
        { ...snapshot, projection: { ...projection, costUsd: 2.5 } },
        undefined,
        undefined,
        { tokens: 99 }
      )
      expect(session.costUsd).toBeNull()
      expect(session.unknownCostCount).toBeNull()
      expect(session.tokens).toBeNull()
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
      const gates = session.phase.sections.find((section) => section.label === 'Validations')
      expect(gates?.count).toBe(1)

      const interactionEvents = session.events.filter((event) => event.text.includes('Approve the deploy?'))
      expect(interactionEvents).toHaveLength(1)
      expect(interactionEvents[0]).toMatchObject({ time: '16:00:00', type: 'agent_message' })
      expect(interactionEvents[0]?.text).toContain('Human validation')
      // The interaction event is additive: the projection step events survive.
      expect(session.events.some((event) => event.type === 'phase_start')).toBe(true)
    })

    it('reports zero gates when no interaction is available', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      const gates = session.phase.sections.find((section) => section.label === 'Validations')
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

      const gates = session.phase.sections.find((section) => section.label === 'Validations')
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

      const outputs = session.phase.sections.find((section) => section.label === 'Outputs')
      expect(outputs?.count).toBe(2)
      expect(outputs?.body).toBeUndefined()
      expect(outputs?.items?.[0]).toMatchObject({ title: 'oracle', status: 'pass', subtitle: 'all tests pass' })
      expect(outputs?.items?.[1]).toMatchObject({ title: 'result', subtitle: 'result summary' })
    })

    it('reports an explicit empty message for validations and outputs when nothing is known', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      const gates = session.phase.sections.find((section) => section.label === 'Validations')
      const outputs = session.phase.sections.find((section) => section.label === 'Outputs')
      expect(gates?.body).toBe('No interaction validation recorded for this phase.')
      expect(outputs?.body).toBe('No output recorded for this phase.')
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

      const config = session.phase.sections.find((section) => section.label === 'Agent configuration')
      expect(config?.items).toEqual(
        expect.arrayContaining([
          { title: 'Agent', subtitle: 'builder' },
          { title: 'Case', subtitle: 'case-7' },
          { title: 'Attempts', subtitle: '2/1' },
        ])
      )

      const prompts = session.phase.sections.find((section) => section.label === 'Attempt mandate')
      expect(prompts).toMatchObject({ notAvailable: true })
      expect(prompts?.count).toBeUndefined()

      const model = session.phase.sections.find((section) => section.label === 'Resolved LLM model')
      expect(model).toMatchObject({ notAvailable: true, body: 'Not recorded for this attempt.' })
    })

    it('uses the resolved model of the exact selected attempt', () => {
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'first',
          status: 'failed',
          caseId: 'case-1',
          resolvedModel: { provider: 'openai', model: 'gpt-4.1' },
        },
        {
          attemptId: 'a-2',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'second',
          status: 'running',
          caseId: 'case-2',
          resolvedModel: { provider: 'anthropic', model: 'claude-sonnet' },
        },
      ]
      const details = mapProjectionToPhaseByAttemptId(projection.steps, 'running', [], attempts)
      const selectedModel = (attemptId: string) =>
        details.get(attemptId)?.sections.find((section) => section.label === 'Resolved LLM model')?.body
      expect(selectedModel('a-1')).toBe('openai / gpt-4.1')
      expect(selectedModel('a-2')).toBe('anthropic / claude-sonnet')
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

    it('distinguishes a missing attempt, a non-dispatched step, and a failed attempt read', () => {
      const noAttempts = mapProjectionToSessionDetail(snapshot)
      expect(noAttempts.phase.attempt).toBe('No attempt recorded')
      expect(noAttempts.phase.attempts).toBeUndefined()

      const emptyAttempts = mapProjectionToSessionDetail(snapshot, undefined, undefined, undefined, undefined, [])
      expect(emptyAttempts.phase.attempt).toBe('No attempt recorded')
      expect(emptyAttempts.attempts).toEqual([])

      const pending = mapProjectionToSessionDetail({
        workflowId: 'pending',
        projection: { steps: [{ id: 'agent', status: 'pending', responsibility: { kind: 'agent', name: 'Dev' } }] },
      })
      expect(pending.phase.attempt).toBe('Not dispatched')

      const unavailable = mapProjectionToSessionDetail(
        snapshot,
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        'Attempts service unavailable'
      )
      expect(unavailable.phase.attempt).toBe('Attempts unavailable')
      expect(unavailable.phase.attemptsLoadError).toBe('Attempts service unavailable')
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

  describe('mapProjectionToPhaseByAttemptId', () => {
    it('builds exact retry details rather than silently using the latest attempt', () => {
      const attempts = [
        {
          attemptId: 'a-1',
          stepId: 'build',
          attemptNumber: 1,
          agentName: 'engineer',
          status: 'failed',
          caseId: 'case-1',
          failureCode: 'FIRST_FAILED',
        },
        {
          attemptId: 'a-2',
          stepId: 'build',
          attemptNumber: 2,
          agentName: 'architect',
          status: 'waiting_human',
          caseId: 'case-2',
        },
      ]
      const map = mapProjectionToPhaseByAttemptId(projection.steps, 'running', [], attempts)

      expect(map.get('a-1')).toMatchObject({
        stepId: 'build',
        currentAttemptNumber: 1,
        agentName: 'engineer',
        caseId: 'case-1',
        failureCode: 'FIRST_FAILED',
      })
      expect(map.get('a-2')).toMatchObject({
        stepId: 'build',
        currentAttemptNumber: 2,
        agentName: 'architect',
        caseId: 'case-2',
      })
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

  describe('outcome transition projection', () => {
    it('renders historical executions of every responsibility with cycle links and exact execution evidence', () => {
      const workflow = {
        workflowId: 'correction-run',
        projection: {
          status: 'running',
          correction: {
            cycleId: 'cycle-1',
            cycleNumber: 1,
            maxCycles: 2,
            status: 'active',
            triggeringStepId: 'review',
            returnTargetStepId: 'implement',
          },
          stepExecutions: [
            {
              executionId: 'exec-implement-1',
              stepId: 'implement',
              executionNumber: 1,
              status: 'completed',
              startedAt,
              completedAt: isoOffset(10),
            },
            {
              executionId: 'exec-implement-2',
              stepId: 'implement',
              executionNumber: 2,
              cycleNumber: 1,
              status: 'running',
              startedAt: isoOffset(20),
              triggeringExecutionId: 'exec-review-1',
            },
          ],
          steps: [
            { id: 'implement', name: 'Implement', status: 'running', responsibility: { kind: 'code', name: 'check' } },
          ],
        },
      }
      const evidence = {
        items: [
          {
            evidenceId: 'e-1',
            executionId: 'exec-implement-1',
            kind: 'log',
            facts: { text: 'old execution' },
            createdAt: startedAt,
          },
          {
            evidenceId: 'e-2',
            executionId: 'exec-implement-2',
            kind: 'log',
            facts: { text: 'new execution' },
            createdAt: isoOffset(20),
          },
        ],
      }
      const session = mapProjectionToSessionDetail(workflow, undefined, evidence)
      expect(session.correction).toMatchObject({ cycleNumber: 1, maxCycles: 2, returnTargetStepId: 'implement' })
      expect(session.lanes.flatMap((lane) => lane.blocks)).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ executionId: 'exec-implement-1', executionNumber: 1 }),
          expect.objectContaining({
            executionId: 'exec-implement-2',
            cycleNumber: 1,
            triggeringExecutionId: 'exec-review-1',
          }),
        ])
      )
      expect(session.eventsByExecutionId?.get('exec-implement-1')?.map((event) => event.text)).toEqual([
        'old execution',
      ])
      expect(session.eventsByExecutionId?.get('exec-implement-2')?.map((event) => event.text)).toEqual([
        'new execution',
      ])
      expect(session.lanes.flatMap((lane) => lane.blocks).filter((block) => block.stepId === 'implement')).toHaveLength(
        2
      )
    })
  })

  describe('outcome transition with return and budget left (WZ-36374)', () => {
    const reviewStep = (executionId: string, status: string) => ({
      id: 'code-review',
      name: 'Code review',
      status,
      executionId,
      dependsOn: ['implement'],
      responsibility: { kind: 'agent', name: 'Reviewer' },
    })
    const replayedSteps = (reviewStatus: string, reviewExecutionId: string, others: string, suffix: string) => [
      {
        id: 'tech-assessment',
        name: 'Tech assessment',
        status: others,
        executionId: `ta-${suffix}`,
        responsibility: { kind: 'agent', name: 'Architect' },
      },
      {
        id: 'implement',
        name: 'Implement',
        status: others,
        executionId: `im-${suffix}`,
        dependsOn: ['tech-assessment'],
        responsibility: { kind: 'agent', name: 'Builder' },
      },
      reviewStep(reviewExecutionId, reviewStatus),
    ]
    const ledger = [
      { executionId: 'ta-1', stepId: 'tech-assessment', executionNumber: 1, cycleNumber: 0, status: 'completed' },
      { executionId: 'im-1', stepId: 'implement', executionNumber: 1, cycleNumber: 0, status: 'completed' },
      {
        executionId: 'cr-1',
        stepId: 'code-review',
        executionNumber: 1,
        cycleNumber: 0,
        status: 'failed',
        attemptId: 'attempt-cr-1',
      },
    ]
    const replayedLedger = [
      ...ledger,
      {
        executionId: 'ta-2',
        stepId: 'tech-assessment',
        executionNumber: 2,
        cycleNumber: 1,
        status: 'pending',
        triggeringExecutionId: 'cr-1',
      },
      {
        executionId: 'im-2',
        stepId: 'implement',
        executionNumber: 2,
        cycleNumber: 1,
        status: 'pending',
        triggeringExecutionId: 'cr-1',
      },
      {
        executionId: 'cr-2',
        stepId: 'code-review',
        executionNumber: 2,
        cycleNumber: 1,
        status: 'pending',
        triggeringExecutionId: 'cr-1',
      },
    ]
    const correction = {
      cycleId: 'cycle-1',
      cycleNumber: 1,
      maxCycles: 2,
      status: 'active',
      triggeringStepId: 'code-review',
      returnTargetStepId: 'tech-assessment',
    }
    const evidenceOf = (executionId: string, stepId: string, status: string, offset: number) => ({
      evidenceId: `ev-${executionId}`,
      stepId,
      kind: 'session-step',
      facts: { stepId, executionId, status, startedAt: isoOffset(offset), durationMs: 5_000 },
    })
    const previousExecutionEvidence = {
      items: [
        evidenceOf('ta-1', 'tech-assessment', 'completed', 0),
        evidenceOf('im-1', 'implement', 'completed', 10),
        evidenceOf('cr-1', 'code-review', 'failed', 20),
      ],
    }

    it('keeps the run active and the replayed steps pending after an accepted FAIL with return', () => {
      const session = mapProjectionToSessionDetail(
        {
          workflowId: 'wz-36374',
          projection: {
            status: 'ready',
            correction,
            stepExecutions: replayedLedger,
            steps: replayedSteps('pending', 'cr-2', 'pending', '2'),
          },
        },
        undefined,
        previousExecutionEvidence
      )

      expect(session.status).not.toBe('failed')
      expect(session.status).toBe('queued')
      expect(session.executionState).toBe('pending')
      expect(session.steps.map((step) => step.status)).toEqual(['pending', 'pending', 'pending'])
      // AC2: the rejected attempt stays visible, with its FAIL, in the execution history.
      const rejected = session.lanes.flatMap((lane) => lane.blocks).find((block) => block.executionId === 'cr-1')
      expect(rejected).toMatchObject({ stepId: 'code-review', status: 'failed' })
      expect(mapSessionDetailToRunSummary({ workflowId: 'wz-36374' }, session).cardState).toBe('pending')
    })

    it('keeps evidence of the current execution on the step', () => {
      const session = mapProjectionToSessionDetail(
        {
          workflowId: 'wz-36374',
          projection: {
            status: 'running',
            correction,
            stepExecutions: replayedLedger,
            steps: replayedSteps('pending', 'cr-2', 'completed', '2'),
          },
        },
        undefined,
        {
          items: [...previousExecutionEvidence.items, evidenceOf('cr-2', 'code-review', 'running', 30)],
        }
      )

      expect(session.steps.find((step) => step.key === 'code-review')?.status).toBe('running')
      expect(session.status).toBe('running')
    })

    it('does not flip the run to failed in the window between the FAIL and the replay', () => {
      const session = mapProjectionToSessionDetail(
        {
          workflowId: 'wz-36374',
          projection: {
            status: 'running',
            stepExecutions: ledger,
            steps: replayedSteps('failed', 'cr-1', 'completed', '1'),
          },
        },
        undefined,
        previousExecutionEvidence
      )

      expect(session.status).toBe('running')
    })

    it('waits for a human when the correction budget is exhausted', () => {
      const session = mapProjectionToSessionDetail({
        workflowId: 'wz-36374',
        projection: {
          status: 'waiting_human',
          correction: { ...correction, cycleNumber: 2, status: 'waiting_human' },
          stepExecutions: ledger,
          steps: replayedSteps('failed', 'cr-1', 'completed', '1'),
        },
      })

      expect(session.executionState).toBe('waiting_human')
      expect(session.status).toBe('running')
    })

    it('still ends as failed when the backend publishes a terminal failed status (technical failure)', () => {
      const session = mapProjectionToSessionDetail({
        workflowId: 'wz-36374',
        projection: {
          status: 'failed',
          stepExecutions: ledger,
          steps: replayedSteps('failed', 'cr-1', 'completed', '1'),
        },
      })

      expect(session.status).toBe('failed')
      expect(session.executionState).toBe('failed')
    })

    it('keeps a legacy run without execution ledger failed when a step failed', () => {
      const session = mapProjectionToSessionDetail({
        workflowId: 'legacy',
        projection: {
          status: 'running',
          steps: [{ id: 'build', status: 'failed', responsibility: { kind: 'code', name: 'build' } }],
        },
      })

      expect(session.status).toBe('failed')
    })
  })

  describe('outcome execution projection', () => {
    it('keeps each ledger execution and its evidence isolated across correction cycles', () => {
      const workflow = {
        workflowId: 'outcome-run',
        projection: {
          status: 'running',
          stepExecutions: [
            {
              executionId: 'exec-1',
              stepId: 'implement',
              executionNumber: 1,
              cycleNumber: 0,
              status: 'completed',
              startedAt,
              completedAt: isoOffset(5),
              resultEvidenceId: 'e-1',
            },
            {
              executionId: 'exec-2',
              stepId: 'implement',
              executionNumber: 2,
              cycleNumber: 1,
              status: 'running',
              startedAt: isoOffset(10),
              triggeringExecutionId: 'review-1',
              resultEvidenceId: 'e-2',
            },
          ],
          correction: {
            cycleId: 'cycle-1',
            cycleNumber: 1,
            maxCycles: 2,
            status: 'active',
            triggeringStepId: 'review',
            triggeringExecutionId: 'review-1',
            returnTargetStepId: 'implement',
          },
          steps: [
            { id: 'implement', name: 'Implement', status: 'running', responsibility: { kind: 'code', name: 'build' } },
          ],
        },
      }
      const session = mapProjectionToSessionDetail(workflow, undefined, {
        items: [
          {
            evidenceId: 'e-1',
            stepId: 'implement',
            kind: 'session-step',
            facts: { executionId: 'exec-1', text: 'first proof' },
          },
          {
            evidenceId: 'e-2',
            stepId: 'implement',
            kind: 'session-step',
            facts: { executionId: 'exec-2', text: 'second proof' },
          },
        ],
      })
      expect(session.correction).toMatchObject({ cycleNumber: 1, maxCycles: 2, status: 'active' })
      expect(session.lanes.flatMap((lane) => lane.blocks)).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ executionId: 'exec-1', executionNumber: 1, cycleNumber: 0 }),
          expect.objectContaining({
            executionId: 'exec-2',
            executionNumber: 2,
            cycleNumber: 1,
            triggeringExecutionId: 'review-1',
          }),
        ])
      )
      expect(session.eventsByExecutionId?.get('exec-1')?.map((event) => event.text)).toEqual(['first proof'])
      expect(session.eventsByExecutionId?.get('exec-2')?.map((event) => event.text)).toEqual(['second proof'])
      expect(
        session.phaseByExecutionId
          ?.get('exec-1')
          ?.sections.find((section) => section.label === 'Outputs')
          ?.items?.map((item) => item.subtitle)
      ).toEqual(['first proof'])
      expect(session.lanes.flatMap((lane) => lane.blocks).filter((block) => block.stepId === 'implement')).toHaveLength(
        2
      )
    })
  })

  describe('external polling (WZ-36393)', () => {
    const pollingStep = (status: string, poll?: Record<string, unknown>) => ({
      id: 'ci',
      name: 'CI checks',
      status,
      startedAt,
      executionId: 'ci-1',
      responsibility: { kind: 'code', name: 'github-pr-checks' },
      ...(poll ? { poll } : {}),
    })
    const poll = {
      executionId: 'ci-1',
      source: 'ci-checks',
      pollCount: 3,
      lastPollAt: isoOffset(120),
      nextPollAt: isoOffset(180),
      deadlineAt: isoOffset(1_800),
      reason: 'CI_CHECKS_PENDING',
    }

    it('shows a waiting_external run as running, never failed nor waiting for a human', () => {
      expect(mapWorkflowStateToRunStatus('waiting_external')).toBe('running')
      const session = mapProjectionToSessionDetail({
        workflowId: 'poll-run',
        projection: { status: 'waiting_external', steps: [pollingStep('waiting_external', poll)] },
      })
      expect(session.status).toBe('running')
      expect(session.executionState).toBe('waiting_external')
      expect(session.activelyRunning).toBe(false)
      expect(session.steps[0]?.status).toBe('waiting_external')
      expect(mapSessionDetailToRunSummary({ workflowId: 'poll-run' }, session).cardState).toBe('running')
    })

    it('renders an AGENT_IN_PROGRESS attempt as a poll and never as a failure', () => {
      const lanes = mapProjectionToLanes(
        {
          projection: {
            steps: [
              { id: 'wait', name: 'Wait', status: 'waiting_external', responsibility: { kind: 'agent', name: 'Dev' } },
            ],
          },
        },
        { startedAt },
        [
          {
            attemptId: 'a-1',
            stepId: 'wait',
            attemptNumber: 1,
            agentName: 'Dev',
            status: 'indeterminate',
            caseId: 'c-1',
            failureCode: 'AGENT_IN_PROGRESS',
            startedAt,
            completedAt: isoOffset(30),
          },
        ]
      )
      expect(lanes[0]?.blocks[0]).toMatchObject({ failureCode: 'AGENT_IN_PROGRESS', status: 'waiting_external' })
    })

    it('renders the External wait section from the poll state', () => {
      const session = mapProjectionToSessionDetail({
        workflowId: 'poll-run',
        projection: { status: 'waiting_external', steps: [pollingStep('waiting_external', poll)] },
      })
      const section = session.phase.sections.find((candidate) => candidate.label === 'External wait')
      expect(section?.items).toEqual(
        expect.arrayContaining([
          { title: 'Poll', subtitle: '#3' },
          { title: 'Last check', subtitle: isoOffset(120) },
          { title: 'Next check', subtitle: isoOffset(180) },
          { title: 'Deadline', subtitle: isoOffset(1_800) },
          { title: 'Reason', subtitle: 'CI_CHECKS_PENDING' },
        ])
      )
    })

    it('has no External wait section for an ordinary step', () => {
      const session = mapProjectionToSessionDetail(snapshot)
      expect(session.phase.sections.some((candidate) => candidate.label === 'External wait')).toBe(false)
    })

    it('ends a polling block at its last observation instead of the wall clock', () => {
      jest.useFakeTimers().setSystemTime(new Date(isoOffset(5_000)))
      try {
        const lanes = mapProjectionToLanes({
          projection: { steps: [pollingStep('waiting_external', poll)] },
        })
        expect(lanes.find((lane) => lane.id === 'code')?.blocks[0]).toMatchObject({
          status: 'waiting_external',
          startSec: 0,
          endSec: 120,
        })
      } finally {
        jest.useRealTimers()
      }
    })

    it('a CI timeout still ends the run as failed', () => {
      const session = mapProjectionToSessionDetail({
        workflowId: 'poll-timeout',
        projection: { status: 'failed', steps: [pollingStep('failed')] },
      })
      expect(session.status).toBe('failed')
    })
  })

  describe('ci-checks evidence (WZ-36393)', () => {
    const stepOf = (status: string) => ({
      id: 'ci',
      name: 'CI checks',
      status,
      startedAt,
      executionId: 'ci-1',
      responsibility: { kind: 'code', name: 'github-pr-checks' },
    })
    const ciEvidence = (verdict: string, extra: Record<string, unknown> = {}) => ({
      evidenceId: `ev-${verdict}`,
      stepId: 'ci',
      kind: 'ci-checks',
      outcome: verdict === 'passed' ? 'pass' : verdict === 'failed' ? 'fail' : 'pending',
      createdAt: startedAt,
      facts: {
        stepId: 'ci',
        executionId: 'ci-1',
        verdict,
        prNumber: 7,
        headSha: '0123456789abcdef0123456789abcdef01234567',
        ...extra,
      },
    })
    const detailOf = (item: unknown, status = 'completed') =>
      mapProjectionToSessionDetail(
        { workflowId: 'ci-run', projection: { status: 'running', steps: [stepOf(status)] } },
        undefined,
        { items: [item] }
      )

    it('renders a red verdict as an Outputs item with the failed check names and no log excerpt', () => {
      const session = detailOf(
        ciEvidence('failed', {
          failedChecks: [
            { name: 'validate-jvm', conclusion: 'failure', logExcerpt: 'SECRET-LOG-LINE' },
            { name: 'merge-gate', conclusion: 'timed_out' },
          ],
        }),
        'failed'
      )
      const outputs = session.phase.sections.find((section) => section.label === 'Outputs')
      expect(outputs?.items).toEqual([
        {
          title: 'CI checks',
          status: 'failed',
          subtitle: 'PR #7 · 01234567',
          details: 'validate-jvm: failure\nmerge-gate: timed_out',
        },
      ])
      expect(JSON.stringify(outputs)).not.toContain('SECRET-LOG-LINE')
    })

    it('renders a green verdict without details', () => {
      const outputs = detailOf(ciEvidence('passed')).phase.sections.find((section) => section.label === 'Outputs')
      expect(outputs?.items).toEqual([{ title: 'CI checks', status: 'passed', subtitle: 'PR #7 · 01234567' }])
    })

    it('emits one log event per verdict, without inlining the log excerpt', () => {
      const red = detailOf(
        ciEvidence('failed', { failedChecks: [{ name: 'validate-jvm', conclusion: 'failure', logExcerpt: 'SECRET' }] }),
        'failed'
      )
      expect(red.events).toEqual([
        expect.objectContaining({ type: 'log', text: 'CI checks failed on PR #7: validate-jvm' }),
      ])
      expect(JSON.stringify(red.events)).not.toContain('SECRET')
      const green = detailOf(ciEvidence('passed'))
      expect(green.events).toEqual([expect.objectContaining({ type: 'log', text: 'CI checks passed on PR #7' })])
    })

    it('never overrides the projected step status with a ci-checks evidence', () => {
      const session = detailOf(ciEvidence('failed'), 'waiting_external')
      expect(session.steps[0]?.status).toBe('waiting_external')
      expect(session.phase.stepStatus).toBe('waiting_external')
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

  describe('resolveMainCaseId', () => {
    const attempt = (overrides: Record<string, unknown>) => ({
      attemptId: 'a',
      stepId: 'plan',
      attemptNumber: 1,
      agentName: 'planner',
      status: 'completed',
      caseId: 'case-x',
      ...overrides,
    })
    /** No controller case: the usual state of a cockpit-created run on the durable bridge. */
    const noControllerSnapshot = {
      workflowId: 'wf-main',
      namespaceId: 'ns-1',
      projection: {
        title: 'Main case',
        status: 'running',
        steps: [
          { id: 'plan', status: 'completed', responsibility: { kind: 'agent', name: 'planner' } },
          {
            id: 'build',
            status: 'running',
            dependsOn: ['plan'],
            responsibility: { kind: 'agent', name: 'builder' },
          },
        ],
      },
    }

    it('returns the controller case when the snapshot exposes one, even when attempts exist', () => {
      const attempts = extractAttempts([attempt({ caseId: 'case-root' })])
      expect(
        resolveMainCaseId({ ...noControllerSnapshot, controllerExecution: { caseId: 'case-ctl' } }, attempts)
      ).toBe('case-ctl')
    })

    it('resolves the case of the entry agent step attempt, not of a dependent step', () => {
      const attempts = extractAttempts([
        attempt({ attemptId: 'a-build', stepId: 'build', caseId: 'case-b' }),
        attempt({ attemptId: 'a-plan', stepId: 'plan', attemptNumber: 1, caseId: 'case-root' }),
      ])

      expect(resolveMainCaseId(noControllerSnapshot, attempts)).toBe('case-root')
      const detail = mapProjectionToSessionDetail(
        noControllerSnapshot,
        undefined,
        undefined,
        undefined,
        undefined,
        attempts
      )
      expect(detail.controllerCaseId).toBeUndefined()
      expect(detail.mainCaseId).toBe('case-root')
      expect(mapSessionDetailToRunSummary(noControllerSnapshot, detail).mainCaseId).toBe('case-root')
    })

    it('uses the lowest attempt number of the entry step that has a case', () => {
      const attempts = extractAttempts([
        attempt({ attemptId: 'a-2', attemptNumber: 2, caseId: 'case-retry' }),
        attempt({ attemptId: 'a-1', attemptNumber: 1, caseId: 'case-first' }),
      ])
      expect(resolveMainCaseId(noControllerSnapshot, attempts)).toBe('case-first')
    })

    it('falls back to the earliest attempt by createdAt when no unique entry agent step exists', () => {
      const ambiguous = {
        ...noControllerSnapshot,
        projection: {
          ...noControllerSnapshot.projection,
          steps: [
            { id: 'plan', responsibility: { kind: 'agent' } },
            { id: 'design', responsibility: { kind: 'agent' } },
          ],
        },
      }
      const attempts = extractAttempts([
        attempt({ attemptId: 'late', stepId: 'plan', caseId: 'case-late', createdAt: isoOffset(30) }),
        attempt({ attemptId: 'early', stepId: 'design', caseId: 'case-early', createdAt: isoOffset(10) }),
        attempt({ attemptId: 'unknown', stepId: 'plan', caseId: 'case-unknown' }),
      ])
      expect(resolveMainCaseId(ambiguous, attempts)).toBe('case-early')
    })

    it('ignores attempts without a case id', () => {
      const attempts = extractAttempts([attempt({ caseId: '' })])
      expect(resolveMainCaseId(noControllerSnapshot, attempts)).toBeUndefined()
    })

    it('is undefined without controller case and without attempt, and the run card then carries none', () => {
      expect(resolveMainCaseId(noControllerSnapshot)).toBeUndefined()
      expect(mapProjectionToFactoryRun(noControllerSnapshot).mainCaseId).toBeUndefined()
      expect(mapProjectionToSessionDetail(noControllerSnapshot).mainCaseId).toBeUndefined()
    })

    it('carries the controller case as main case on the run before any enrichment', () => {
      const withController = { ...noControllerSnapshot, controllerExecution: { caseId: 'case-ctl' } }
      expect(mapProjectionToFactoryRun(withController).mainCaseId).toBe('case-ctl')
    })
  })
})
