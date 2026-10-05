import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { FactoryStore } from './factory.store'
import { EVENT_SOURCE_FACTORY } from './sse.service'

type Listener = (event: MessageEvent) => void

/** Minimal in-memory `EventSource` double (jsdom has no implementation). */
class FakeEventSource {
  static instances: FakeEventSource[] = []

  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  closed = false
  private readonly listeners = new Map<string, Listener[]>()

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    const bucket = this.listeners.get(type) ?? []
    bucket.push(listener)
    this.listeners.set(type, bucket)
  }

  close(): void {
    this.closed = true
  }

  emit(type: string, data: string): void {
    for (const listener of this.listeners.get(type) ?? []) listener({ data } as MessageEvent)
  }
}

const startedAt = '2026-09-30T16:00:00.000Z'

const snapshot = {
  workflowId: 'wf-1',
  namespaceId: 'ns-1',
  revision: 2,
  relations: { rootWorkflowId: 'wf-1', ticket: 'ABC-1' },
  projection: {
    schemaVersion: '2',
    title: 'Real workflow',
    status: 'running',
    steps: [
      {
        id: 'plan',
        name: 'plan',
        status: 'completed',
        responsibility: { kind: 'agent', name: 'planner' },
        startedAt,
        durationMs: 30_000,
      },
      {
        id: 'build',
        name: 'build',
        status: 'running',
        responsibility: { kind: 'code' },
        startedAt,
        durationMs: 60_000,
      },
    ],
  },
}

describe('FactoryStore', () => {
  let store: FactoryStore
  let http: HttpTestingController

  beforeEach(() => {
    FakeEventSource.instances = []
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => new FakeEventSource(url) as unknown as EventSource,
        },
      ],
    })
    store = TestBed.inject(FactoryStore)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => http.verify())

  function flushInitialWorkflows(items: unknown[] = [], removed: unknown[] = []): void {
    const activeRequest = http.expectOne(
      (r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active'
    )
    const removedRequest = http.expectOne(
      (r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed'
    )
    activeRequest.flush({ data: { namespaceId: '', state: 'active', items } })
    removedRequest.flush({ data: { namespaceId: '', state: 'removed', items: removed } })
  }

  function flushEnrichment(
    metrics: unknown = { workflowId: 'wf-1' },
    interactions: unknown = { workflowId: 'wf-1', items: [] },
    attempts: unknown = { workflowId: 'wf-1', data: [] },
    actions: unknown = { allowedActions: [], blockers: [] }
  ): void {
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: metrics })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: interactions })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush(attempts)
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: actions })
  }

  // ---------------------------------------------------------------------------
  // Derivation from the real active workflows
  // ---------------------------------------------------------------------------

  it('starts with no sandboxes before any real workflow is loaded', () => {
    flushInitialWorkflows()

    expect(store.sandboxes()).toEqual([])
    expect(store.activeSandboxes()).toEqual([])
    expect(store.costs()).toEqual({ active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 })
  })

  it('derives one sandbox per active workflow, from the real snapshot fields', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.sandboxes()).toHaveLength(1)
    const [sandbox] = store.sandboxes()
    if (!sandbox) throw new Error('expected one derived sandbox')

    expect(sandbox.name).toBe('Real workflow')
    expect(sandbox.project).toBe('ns-1')
    expect(sandbox.namespace).toBe('ns-1')
    expect(sandbox.ticket).toBe('ABC-1')
    expect(sandbox.branch).toBe('ABC-1')
    expect(sandbox.status).toBe('working')
    expect(sandbox.run?.id).toBe('wf-1')
    expect(sandbox.run?.workflow).toBe('Real workflow')
    expect(sandbox.run?.status).toBe('running')
  })

  it('maps an idle/pending workflow state onto an idle sandbox', () => {
    const idle = { ...snapshot, projection: { ...snapshot.projection, status: 'pending' } }
    flushInitialWorkflows([idle])
    flushEnrichment()

    expect(store.sandboxes()[0]?.status).toBe('idle')
  })

  it('keeps the active and visible lists consistent and toggles with showDestroyed', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.visibleSandboxes()).toEqual(store.activeSandboxes())
    expect(store.destroyedSandboxes()).toEqual([])

    store.showDestroyed.set(true)
    expect(store.visibleSandboxes()).toEqual(store.sandboxes())
  })

  // ---------------------------------------------------------------------------
  // Real cost aggregation
  // ---------------------------------------------------------------------------

  it('aggregates the real workflow costs and uncertainty into CostSummary', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment({
      workflowId: 'wf-1',
      realCost: {
        cost: 1.5,
        unknownCostCount: 2,
        liveTokens: 150,
        paused: false,
        active: true,
        runCostThreshold: null,
      },
    })

    const costs = store.costs()
    expect(costs.active).toBe(1)
    expect(costs.workflowsUsd).toBeCloseTo(1.5, 6)
    expect(costs.totalUsd).toBeCloseTo(1.5, 6)
    expect(costs.unknownCostCount).toBe(2)
    // No fabricated Archay/destroyed figures anymore.
    expect(costs.archayUsd).toBeUndefined()
    expect(costs.destroyedUsd).toBeUndefined()
  })

  // ---------------------------------------------------------------------------
  // Session detail + governed actions (preserved surface)
  // ---------------------------------------------------------------------------

  it('exposes a real session detail for a loaded workflow', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.workflow).toBe('Real workflow')
    expect(session?.status).toBe('running')
  })

  it('enriches the session and its sandbox run with the real cost from metrics', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment({
      workflowId: 'wf-1',
      realCost: {
        cost: 1.0723,
        unknownCostCount: 0,
        liveTokens: 150,
        paused: false,
        active: true,
        runCostThreshold: null,
      },
    })

    expect(store.session('wf-1')?.costUsd).toBe(1.0723)
    expect(store.activeSandboxes()[0]?.run?.costUsd).toBe(1.0723)
    expect(store.activeSandboxes()[0]?.run?.unknownCostCount).toBe(0)
    expect(store.costs().workflowsUsd).toBeCloseTo(1.0723, 6)
  })

  it('propagates a partially unknown workflow cost into the summary', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment({
      workflowId: 'wf-1',
      realCost: { cost: 0.5, unknownCostCount: 3, liveTokens: 0, paused: false, active: true, runCostThreshold: null },
    })

    expect(store.session('wf-1')?.unknownCostCount).toBe(3)
    expect(store.activeSandboxes()[0]?.run?.unknownCostCount).toBe(3)
    expect(store.costs().unknownCostCount).toBe(3)
    expect(store.costs().totalUsd).toBeCloseTo(0.5, 6)
  })

  it('enriches the session with read-only human interactions', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment(
      { workflowId: 'wf-1' },
      {
        workflowId: 'wf-1',
        items: [
          {
            interactionId: 'i-1',
            stepId: 'build',
            interactionType: 'approval',
            status: 'waiting',
            payload: { prompt: 'Approve the deploy?' },
          },
        ],
      }
    )

    const session = store.session('wf-1')
    expect(session?.interactions).toHaveLength(1)
    expect(session?.interactions?.[0]?.interactionId).toBe('i-1')
    expect(session?.phase.sections.find((section) => section.label === 'Gates')?.count).toBe(1)
    expect(session?.events.some((event) => event.text.includes('Approve the deploy?'))).toBe(true)
  })

  it('degrades gracefully when the interactions fetch fails', () => {
    flushInitialWorkflows([snapshot])
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/interactions'))
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.interactions).toEqual([])
    // The other enrichments still landed despite the failed interaction fetch.
    expect(session?.workflow).toBe('Real workflow')
  })

  it('enriches the session with real agent attempts and the dynamic attempt string', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment(
      { workflowId: 'wf-1' },
      { workflowId: 'wf-1', items: [] },
      {
        workflowId: 'wf-1',
        data: [
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
        ],
      }
    )

    const session = store.session('wf-1')
    expect(session?.attempts).toHaveLength(2)
    expect(session?.phase.attempt).toBe('2/2')
    expect(session?.phase.currentAttemptNumber).toBe(2)
    expect(session?.phase.totalAttempts).toBe(2)
    expect(session?.phase.attemptStatus).toBe('running')
    expect(session?.phase.attempt).toBe('2/2')
  })

  it('degrades gracefully when the attempts fetch fails', () => {
    flushInitialWorkflows([snapshot])
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/attempts'))
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.attempts).toEqual([])
    // Fallback attempt string is neutral.
    expect(session?.phase.attempt).toBe('1/1')
    expect(session?.workflow).toBe('Real workflow')
  })

  // ---------------------------------------------------------------------------
  // SSE invalidation + graceful degradation
  // ---------------------------------------------------------------------------

  it('refetches the real active workflows on an SSE invalidation', () => {
    flushInitialWorkflows([])
    expect(store.sandboxes()).toEqual([])

    const source = FakeEventSource.instances[0]
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1' }))

    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.sandboxes()).toHaveLength(1)
    expect(store.sandboxes()[0]?.run?.id).toBe('wf-1')
  })

  it('degrades to an empty list (never the mock fleet) when the REST backend fails', () => {
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active')
      .flush({ error: { code: 'UNAVAILABLE' } }, { status: 503, statusText: 'Service Unavailable' })
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed')
      .flush({ error: { code: 'UNAVAILABLE' } }, { status: 503, statusText: 'Service Unavailable' })

    expect(store.sandboxes()).toEqual([])
    expect(store.activeSandboxes()).toEqual([])
    expect(store.costs()).toEqual({ active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 })
    // The demo session fallback still resolves without crashing.
    expect(store.session('872641a8').id).toBe('872641a8')
  })

  it('falls back to the demo session for an unknown run id', () => {
    flushInitialWorkflows()
    const session = store.session('unknown-run')
    expect(session?.id).toBe('unknown-run')
  })

  // ---------------------------------------------------------------------------
  // Governed actions & blockers: backend authority, preserved verbatim
  // ---------------------------------------------------------------------------

  it('enriches the session with backend allowedActions and blockers', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment(
      { workflowId: 'wf-1' },
      { workflowId: 'wf-1', items: [] },
      { workflowId: 'wf-1', data: [] },
      {
        allowedActions: [
          { type: 'retry', stepId: 'build', expectedRevision: 7, label: 'Relancer' },
          { type: 'stop_cost', expectedRevision: 7 },
        ],
        blockers: [{ code: 'STEP_BLOCKED', stepId: 'build', message: 'Step build blocked' }],
      }
    )

    const session = store.session('wf-1')
    expect(session?.allowedActions).toEqual([
      { type: 'retry', stepId: 'build', expectedRevision: 7, label: 'Relancer' },
      { type: 'stop_cost', expectedRevision: 7 },
    ])
    expect(session?.blockers).toEqual([
      { code: 'STEP_BLOCKED', stepId: 'build', label: 'Step build blocked', message: 'Step build blocked' },
    ])
  })

  it('degrades gracefully when the actions fetch fails', () => {
    flushInitialWorkflows([snapshot])
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/actions'))
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.allowedActions).toEqual([])
    expect(session?.blockers).toEqual([])
    expect(session?.workflow).toBe('Real workflow')
  })

  it('replyInteraction posts the reply, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.replyInteraction('wf-1', 'i-1', { actionId: 'approve', text: 'ok', expectedRevision: 3 })

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/interactions/i-1/reply')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({ actionId: 'approve', text: 'ok', expectedRevision: 3 })
    expect(request.request.params.get('namespaceId')).toBe('ns-1')
    expect(request.request.headers.get('X-Correlation-Id')).toBeTruthy()
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  it('retry opens a retry, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.retry('wf-1', { stepId: 'build', expectedRevision: 7, reasonCode: 'human_retry' })

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/retries')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({ stepId: 'build', expectedRevision: 7, reasonCode: 'human_retry' })
    expect(request.request.params.get('namespaceId')).toBe('ns-1')
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  it('cancelAttempt cancels an attempt, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.cancelAttempt('wf-1', 'a-2', { expectedRevision: 4, reason: 'operator' })

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts/a-2/cancel')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({ expectedRevision: 4, reason: 'operator' })
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  it('continueCost relays a threshold, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.continueCost('wf-1', { expectedThreshold: 12 })

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/cost/continue')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({ expectedThreshold: 12 })
    expect(request.request.params.get('namespaceId')).toBe('ns-1')
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  it('stopCost relays a stop, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.stopCost('wf-1')

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/cost/stop')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({})
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  // ---------------------------------------------------------------------------
  // Removed workflows → destroyed sandboxes
  // ---------------------------------------------------------------------------

  it('merges active and removed workflows, mapping removed ones to destroyed sandboxes', () => {
    const removed = {
      ...snapshot,
      workflowId: 'wf-removed',
      relations: { rootWorkflowId: 'wf-removed', ticket: 'ABC-9' },
      projection: { ...snapshot.projection, title: 'Removed workflow', status: 'removed' },
    }
    flushInitialWorkflows([snapshot], [removed])
    flushEnrichment()

    expect(store.sandboxes()).toHaveLength(2)
    expect(store.activeSandboxes()).toHaveLength(1)
    expect(store.destroyedSandboxes()).toHaveLength(1)
    const [destroyed] = store.destroyedSandboxes()
    expect(destroyed?.name).toBe('Removed workflow')
    expect(destroyed?.status).toBe('destroyed')
    // `visibleSandboxes`/`showDestroyed` remain the ONLY visibility filter.
    expect(store.visibleSandboxes()).toEqual(store.activeSandboxes())
    store.showDestroyed.set(true)
    expect(store.visibleSandboxes()).toHaveLength(2)
  })

  it('keeps active sandboxes when the removed fetch fails (partial degradation)', () => {
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active')
      .flush({ data: { namespaceId: '', state: 'active', items: [snapshot] } })
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed')
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    flushEnrichment()

    expect(store.activeSandboxes()).toHaveLength(1)
    expect(store.destroyedSandboxes()).toEqual([])
  })

  // ---------------------------------------------------------------------------
  // stop / remove / restore governed actions
  // ---------------------------------------------------------------------------

  it('stop cancels the active attempt resolved from the cancel_attempt allowed action', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment(
      { workflowId: 'wf-1' },
      { workflowId: 'wf-1', items: [] },
      { workflowId: 'wf-1', data: [] },
      { allowedActions: [{ type: 'cancel_attempt', attemptId: 'a-1', expectedRevision: 5 }], blockers: [] }
    )

    store.stop('wf-1')

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts/a-1/cancel')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({ expectedRevision: 5, reason: 'stop' })
    expect(request.request.params.get('namespaceId')).toBe('ns-1')
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  it('stop resolves the attempt and revision from a real running attempt', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment(
      { workflowId: 'wf-1' },
      { workflowId: 'wf-1', items: [] },
      {
        workflowId: 'wf-1',
        data: [
          {
            attemptId: 'a-9',
            stepId: 'build',
            attemptNumber: 1,
            agentName: 'builder',
            status: 'running',
            caseId: 'case-9',
            revision: 3,
          },
        ],
      },
      { allowedActions: [], blockers: [] }
    )

    store.stop('wf-1')

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/attempts/a-9/cancel')
    expect(request.request.body).toEqual({ expectedRevision: 3, reason: 'stop' })
    request.flush({ data: { ok: true } })

    flushInitialWorkflows([])
  })

  it('stop does nothing (never fabricates) when no active attempt is resolvable', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.stop('wf-1')

    http.expectNone((r) => r.url.includes('/cancel'))
    // No reload is triggered either: the state is left untouched.
    http.expectNone((r) => r.url === '/api/factory/workflows')
  })

  it('remove deletes the workflow, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.remove('wf-1')

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1')
    expect(request.request.method).toBe('DELETE')
    expect(request.request.params.get('namespaceId')).toBe('ns-1')
    request.flush({ data: { status: 'removed' } })

    flushInitialWorkflows([])
  })

  it('remove degrades silently on failure', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.remove('wf-1')

    http
      .expectOne((r) => r.url === '/api/factory/workflows/wf-1')
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })

    // No reload on failure: state is untouched.
    http.expectNone((r) => r.url === '/api/factory/workflows')
  })

  it('restore posts to /restore, then refetches the authoritative state', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.restore('wf-1')

    const request = http.expectOne((r) => r.url === '/api/factory/workflows/wf-1/restore')
    expect(request.request.method).toBe('POST')
    expect(request.request.body).toEqual({})
    expect(request.request.params.get('namespaceId')).toBe('ns-1')
    request.flush({ data: { status: 'active' } })

    flushInitialWorkflows([])
  })

  it('restore degrades silently on failure', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.restore('wf-1')

    http
      .expectOne((r) => r.url === '/api/factory/workflows/wf-1/restore')
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })

    http.expectNone((r) => r.url === '/api/factory/workflows')
  })
})
