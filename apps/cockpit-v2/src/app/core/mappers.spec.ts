import {
  classifyActorKind,
  classifyLane,
  extractAllowedActions,
  extractAttempts,
  extractBlockers,
  extractInteractions,
  extractRealCost,
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
})
