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

/** Build a minimal workflow snapshot with a distinct identity. */
function snapshotFor(workflowId: string, namespaceId: string, title: string, status = 'running'): unknown {
  return {
    workflowId,
    namespaceId,
    relations: { rootWorkflowId: workflowId, ticket: `T-${workflowId}` },
    projection: { schemaVersion: '2', title, status, steps: [] },
  }
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

  function flushNamespaces(items: unknown = []): void {
    http.expectOne((r) => r.url === '/api/namespaces').flush({ data: items })
  }

  function flushInitialWorkflows(items: unknown[] = [], removed: unknown[] = [], namespaces: unknown = []): void {
    const activeRequest = http.expectOne(
      (r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active'
    )
    const removedRequest = http.expectOne(
      (r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed'
    )
    activeRequest.flush({ data: { namespaceId: '', state: 'active', items } })
    removedRequest.flush({ data: { namespaceId: '', state: 'removed', items: removed } })
    flushNamespaces(namespaces)
  }

  function flushEnrichment(
    metrics: unknown = { workflowId: 'wf-1' },
    interactions: unknown = { workflowId: 'wf-1', items: [] },
    attempts: unknown = { workflowId: 'wf-1', data: [] },
    actions: unknown = { allowedActions: [], blockers: [] }
  ): void {
    store.selectSession('wf-1')
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: metrics })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: interactions })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush(attempts)
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: actions })
    http.expectOne((r) => r.url.endsWith('/wf-1/agent-questions')).flush({ data: [] })
  }

  /** Flush the 7 enrichment requests issued for an arbitrary active run. */
  function flushRunEnrichment(wfId: string, select = true): void {
    if (select) store.selectSession(wfId)
    http.expectOne((r) => r.url.endsWith(`/${wfId}/timing`)).flush({ data: { workflowId: wfId, startedAt } })
    http.expectOne((r) => r.url.endsWith(`/${wfId}/evidence`)).flush({ data: { workflowId: wfId, items: [] } })
    http.expectOne((r) => r.url.endsWith(`/${wfId}/metrics`)).flush({ data: { workflowId: wfId } })
    http.expectOne((r) => r.url.endsWith(`/${wfId}/interactions`)).flush({ data: { workflowId: wfId, items: [] } })
    http.expectOne((r) => r.url.endsWith(`/${wfId}/attempts`)).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith(`/${wfId}/actions`)).flush({ data: { allowedActions: [], blockers: [] } })
    http.expectOne((r) => r.url.endsWith(`/${wfId}/agent-questions`)).flush({ data: [] })
  }

  // ---------------------------------------------------------------------------
  // Derivation from the real active workflows
  // ---------------------------------------------------------------------------

  it('starts with no runs before any real workflow is loaded', () => {
    flushInitialWorkflows()

    expect(store.runs()).toEqual([])
    expect(store.activeRuns()).toEqual([])
    expect(store.workstreams()).toEqual([])
    expect(store.costs()).toEqual({ active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 })
  })

  it('loads bounded enrichment for every visible run so cards and detail share truth', () => {
    flushInitialWorkflows([snapshot, snapshotFor('wf-2', 'ns-2', 'Second')])

    expect(store.runs()).toHaveLength(2)
    flushRunEnrichment('wf-1', false)
    flushRunEnrichment('wf-2', false)
  })

  it('reconstructs the same enriched card state after a fresh store reload', () => {
    flushInitialWorkflows([snapshot])
    flushRunEnrichment('wf-1', false)
    const first = store.runs()[0]?.run

    // Simulate authoritative reload through the public refresh path.
    store.refresh()
    flushInitialWorkflows([snapshot])
    flushRunEnrichment('wf-1', false)
    const reloaded = store.runs()[0]?.run

    expect(reloaded?.status).toBe(first?.status)
    expect(reloaded?.phases.map((phase) => phase.status)).toEqual(first?.phases.map((phase) => phase.status))
    expect(reloaded?.costUsd).toBe(first?.costUsd)
  })

  it('fetches detail only for the selected visible session', () => {
    flushInitialWorkflows([snapshot, snapshotFor('wf-2', 'ns-2', 'Second')])

    flushEnrichment()
    http.expectNone((request) => request.url.includes('/wf-2/'))

    expect(store.session('wf-1')?.id).toBe('wf-1')
  })

  it('coalesces overview enrichment bursts per workflow with one trailing refresh', () => {
    flushInitialWorkflows([snapshot])
    const first = http.match((request) => request.url.includes('/wf-1/'))
    const source = FakeEventSource.instances[0]
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', revision: 3 }))
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', revision: 4 }))
    expect(http.match((request) => request.url.endsWith('/wf-1/evidence'))).toHaveLength(1)
    first.forEach((request) => request.flush({ data: [] }))
    expect(http.match((request) => request.url.endsWith('/wf-1/evidence'))).toHaveLength(1)
  })

  it('coalesces same-run SSE bursts without cancelling the active detail and runs one trailing refresh', () => {
    flushInitialWorkflows([snapshot])
    store.selectSession('wf-1')
    const firstRequests = http.match((request) => request.url.includes('/wf-1/'))
    const firstEvidence = firstRequests.find((request) => request.request.url.endsWith('/evidence'))
    const source = FakeEventSource.instances[0]

    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', revision: 3 }))
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', revision: 4 }))
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', revision: 5 }))

    expect(firstEvidence?.cancelled).toBe(false)
    expect(http.match((request) => request.url.endsWith('/wf-1/evidence'))).toHaveLength(1)
    firstRequests.forEach((request) => request.flush({ data: [] }))

    const trailingRequests = http.match((request) => request.url.includes('/wf-1/'))
    expect(trailingRequests.filter((request) => request.request.url.endsWith('/evidence'))).toHaveLength(1)
    trailingRequests.forEach((request) => request.flush({ data: [] }))

    flushInitialWorkflows([snapshot])
    flushInitialWorkflows([snapshot])
    expect(http.match((request) => request.url.endsWith('/wf-1/evidence'))).toHaveLength(0)
  })

  it('cancels A detail when switching to B and prevents late A overwrite', () => {
    flushInitialWorkflows([snapshot, snapshotFor('wf-2', 'ns-2', 'Second')])

    store.selectSession('wf-1')
    const oldEvidence = http.expectOne((request) => request.url.endsWith('/wf-1/evidence'))
    store.selectSession('wf-2')

    expect(oldEvidence.cancelled).toBe(true)
    http.expectOne((r) => r.url.endsWith('/wf-2/timing')).flush({ data: { workflowId: 'wf-2', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-2/evidence')).flush({ data: { workflowId: 'wf-2', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-2/metrics')).flush({ data: { workflowId: 'wf-2' } })
    http.expectOne((r) => r.url.endsWith('/wf-2/interactions')).flush({ data: { items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-2/attempts')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-2/actions')).flush({ data: { allowedActions: [], blockers: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-2/agent-questions')).flush({ data: [] })
    expect(store.session('wf-2')?.id).toBe('wf-2')
  })

  it('cancels selected detail requests when leaving the session', () => {
    flushInitialWorkflows([snapshot])
    store.selectSession('wf-1')
    const requests = http.match((request) => request.url.includes('/wf-1/'))
    const evidence = requests.find((request) => request.request.url.endsWith('/evidence'))

    store.clearSessionSelection('wf-1')

    expect(evidence?.cancelled).toBe(true)
    expect(requests.every((request) => request.cancelled)).toBe(true)
  })

  it('updates the affected card from enriched SSE state without corrupting another run', () => {
    const second = snapshotFor('wf-2', 'ns-2', 'Second')
    flushInitialWorkflows([snapshot, second])
    flushRunEnrichment('wf-1', false)
    flushRunEnrichment('wf-2', false)

    FakeEventSource.instances[0]?.emit(
      'workflow-projection-updated',
      JSON.stringify({ workflowId: 'wf-1', namespaceId: 'ns-1' })
    )
    const failed = {
      ...snapshot,
      projection: {
        ...snapshot.projection,
        status: 'failed',
        steps: [
          { ...snapshot.projection.steps[0], status: 'completed' },
          { ...snapshot.projection.steps[1], status: 'failed' },
          { id: 'deploy', status: 'pending', responsibility: { kind: 'code' } },
        ],
      },
    }
    flushInitialWorkflows([failed, second])
    flushRunEnrichment('wf-1', false)

    expect(store.runs().find((run) => run.id === 'wf-1')?.run?.cardState).toBe('failed')
    expect(
      store
        .runs()
        .find((run) => run.id === 'wf-1')
        ?.run?.phases.map((phase) => phase.status)
    ).toEqual(['done', 'failed', 'pending'])
    expect(store.runs().find((run) => run.id === 'wf-2')?.title).toBe('Second')
  })

  it('does not refresh selected detail for an unrelated targeted SSE event', () => {
    flushInitialWorkflows([snapshot, snapshotFor('wf-2', 'ns-2', 'Second')])
    flushEnrichment()

    FakeEventSource.instances[0]?.emit(
      'workflow-projection-updated',
      JSON.stringify({ workflowId: 'wf-2', namespaceId: 'ns-2' })
    )
    flushInitialWorkflows([snapshot, snapshotFor('wf-2', 'ns-2', 'Second')])

    http.expectNone((request) => request.url.includes('/wf-1/evidence'))
    flushRunEnrichment('wf-2', false)
  })

  it('derives one FactoryRun per active workflow, from the real snapshot fields', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.runs()).toHaveLength(1)
    const [run] = store.runs()
    if (!run) throw new Error('expected one derived run')

    expect(run.id).toBe('wf-1')
    expect(run.title).toBe('Real workflow')
    expect(run.project).toBe('ns-1')
    expect(run.namespaceId).toBe('ns-1')
    expect(run.ticket).toBe('ABC-1')
    expect(run.branch).toBe('ABC-1')
    expect(run.status).toBe('working')
    expect(run.run?.id).toBe('wf-1')
    expect(run.run?.workflow).toBe('Real workflow')
    expect(run.run?.status).toBe('running')
  })

  it('maps the top-level createdAt from the snapshot to run.createdAt', () => {
    const withCreatedAt = { ...snapshot, createdAt: '2026-09-30T16:00:00.000Z' }
    flushInitialWorkflows([withCreatedAt])
    flushEnrichment()

    expect(store.runs()[0]?.createdAt).toBe('2026-09-30T16:00:00.000Z')
  })

  it('leaves run.createdAt absent when the snapshot has no createdAt field', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.runs()[0]?.createdAt).toBeUndefined()
  })

  it('drops an unparseable createdAt value silently', () => {
    const withBadDate = { ...snapshot, createdAt: 'not-a-date' }
    flushInitialWorkflows([withBadDate])
    flushEnrichment()

    expect(store.runs()[0]?.createdAt).toBeUndefined()
  })

  it('sorts visibleRuns by createdAt descending, unknowns last', () => {
    const older = {
      ...snapshot,
      workflowId: 'wf-older',
      createdAt: '2026-09-01T10:00:00.000Z',
      projection: { ...snapshot.projection, title: 'Older workflow', workflowId: 'wf-older' },
    }
    const newer = {
      ...snapshot,
      workflowId: 'wf-newer',
      createdAt: '2026-09-30T16:00:00.000Z',
      projection: { ...snapshot.projection, title: 'Newer workflow', workflowId: 'wf-newer' },
    }
    const noDate = {
      ...snapshot,
      workflowId: 'wf-nodate',
      projection: { ...snapshot.projection, title: 'No date workflow', workflowId: 'wf-nodate' },
    }
    flushInitialWorkflows([older, newer, noDate])

    const titles = store.visibleRuns().map((run) => run.title)
    expect(titles).toEqual(['Newer workflow', 'Older workflow', 'No date workflow'])
  })

  it('maps an idle/pending workflow state onto an idle run', () => {
    const idle = { ...snapshot, projection: { ...snapshot.projection, status: 'pending' } }
    flushInitialWorkflows([idle])
    flushEnrichment()

    expect(store.runs()[0]?.status).toBe('idle')
  })

  it('keeps the active and visible lists consistent and toggles with showDestroyed', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.visibleRuns()).toHaveLength(store.activeRuns().length)
    expect(store.visibleRuns()[0]?.title).toBe(store.activeRuns()[0]?.title)
    expect(store.destroyedRuns()).toEqual([])

    store.showDestroyed.set(true)
    expect(store.visibleRuns()).toHaveLength(store.runs().length)
  })

  // ---------------------------------------------------------------------------
  // Workstreams grouping (namespaceId)
  // ---------------------------------------------------------------------------

  it('groups runs into workstreams strictly by namespaceId', () => {
    flushInitialWorkflows(
      [snapshotFor('wf-a', 'ns-a', 'Run A'), snapshotFor('wf-b', 'ns-b', 'Run B')],
      [],
      [
        { namespaceId: 'ns-a', name: 'Alpha' },
        { namespaceId: 'ns-b', name: 'Beta' },
      ]
    )
    flushRunEnrichment('wf-a')
    flushRunEnrichment('wf-b')

    const workstreams = store.workstreams()
    expect(workstreams.map((ws) => ws.namespaceId).sort()).toEqual(['ns-a', 'ns-b'])
    expect(workstreams.find((ws) => ws.namespaceId === 'ns-a')?.title).toBe('Alpha')
    expect(workstreams.find((ws) => ws.namespaceId === 'ns-b')?.runs.map((r) => r.id)).toEqual(['wf-b'])
  })

  it('keeps homonym namespace titles in distinct workstreams (never grouped by title)', () => {
    flushInitialWorkflows(
      [snapshotFor('wf-a', 'ns-a', 'Run A'), snapshotFor('wf-b', 'ns-b', 'Run B')],
      [],
      [
        { namespaceId: 'ns-a', name: 'Same name' },
        { namespaceId: 'ns-b', name: 'Same name' },
      ]
    )
    flushRunEnrichment('wf-a')
    flushRunEnrichment('wf-b')

    const workstreams = store.workstreams()
    expect(workstreams).toHaveLength(2)
    expect(workstreams.every((ws) => ws.title === 'Same name')).toBe(true)
    expect(workstreams.map((ws) => ws.namespaceId).sort()).toEqual(['ns-a', 'ns-b'])
  })

  it('puts runs without a namespaceId in an explicit unassigned workstream', () => {
    const orphan = {
      workflowId: 'wf-orphan',
      relations: { rootWorkflowId: 'wf-orphan', ticket: 'T-orphan' },
      projection: { schemaVersion: '2', title: 'Orphan run', status: 'running', steps: [] },
    }
    flushInitialWorkflows([orphan])
    flushRunEnrichment('wf-orphan')

    const [workstream] = store.workstreams()
    expect(workstream?.namespaceId).toBe('unassigned')
    expect(workstream?.title).toBe('Sans namespace')
    expect(workstream?.runs.map((run) => run.id)).toEqual(['wf-orphan'])
    expect(workstream?.runs[0]?.namespaceId).toBeUndefined()
  })

  it('falls back to the namespace id as title when the catalogue is empty', () => {
    flushInitialWorkflows([snapshotFor('wf-a', 'ns-unknown', 'Run A')])
    flushRunEnrichment('wf-a')

    expect(store.workstreams()[0]?.title).toBe('ns-unknown')
  })

  it('degrades gracefully when getNamespaces fails: runs still load and group', () => {
    const activeRequest = http.expectOne(
      (r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active'
    )
    const removedRequest = http.expectOne(
      (r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed'
    )
    activeRequest.flush({ data: { items: [snapshotFor('wf-a', 'ns-a', 'Run A')] } })
    removedRequest.flush({ data: { items: [] } })
    http
      .expectOne((r) => r.url === '/api/namespaces')
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    flushRunEnrichment('wf-a')

    expect(store.runs()).toHaveLength(1)
    expect(store.namespaces().size).toBe(0)
    expect(store.workstreams()[0]?.title).toBe('ns-a')
  })

  // ---------------------------------------------------------------------------
  // Real cost aggregation
  // ---------------------------------------------------------------------------

  it('maps blockers and real metrics into the same card summary as detail', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment(
      { realCost: { cost: 2.5, unknownCostCount: 0, liveTokens: 321 } },
      { items: [] },
      { data: [] },
      { allowedActions: [], blockers: [{ code: 'STEP_BLOCKED', stepId: 'build', message: 'blocked' }] }
    )
    const summary = store.runs()[0]?.run
    expect(summary?.cardState).toBe('blocked')
    expect(summary?.costUsd).toBe(2.5)
    expect(summary?.tokens).toBe(321)
  })

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

  it('enriches the session and its run cost with the real cost from metrics', () => {
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
    expect(store.activeRuns()[0]?.costUsd).toBe(1.0723)
    expect(store.activeRuns()[0]?.run?.costUsd).toBe(1.0723)
    expect(store.activeRuns()[0]?.run?.unknownCostCount).toBe(0)
    expect(store.costs().workflowsUsd).toBeCloseTo(1.0723, 6)
  })

  it('propagates a partially unknown workflow cost into the summary', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment({
      workflowId: 'wf-1',
      realCost: { cost: 0.5, unknownCostCount: 3, liveTokens: 0, paused: false, active: true, runCostThreshold: null },
    })

    expect(store.session('wf-1')?.unknownCostCount).toBe(3)
    expect(store.activeRuns()[0]?.run?.unknownCostCount).toBe(3)
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
    expect(session?.phase.sections.find((section) => section.label === 'Gates')?.count).toBe(1)
    expect(session?.events.some((event) => event.text.includes('Approve the deploy?'))).toBe(true)
  })

  it('degrades gracefully when the interactions fetch fails', () => {
    flushInitialWorkflows([snapshot])
    store.selectSession('wf-1')
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/interactions'))
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/agent-questions')).flush({ data: [] })

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.interactions).toEqual([])
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
  })

  it('degrades gracefully when the attempts fetch fails', () => {
    flushInitialWorkflows([snapshot])
    store.selectSession('wf-1')
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/attempts'))
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/agent-questions')).flush({ data: [] })

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.attempts).toEqual([])
    expect(session?.phase.attempt).toBe('1/1')
    expect(session?.workflow).toBe('Real workflow')
  })

  // ---------------------------------------------------------------------------
  // SSE invalidation + graceful degradation
  // ---------------------------------------------------------------------------

  it('refetches the real active workflows on an SSE invalidation', () => {
    flushInitialWorkflows([])
    expect(store.runs()).toEqual([])

    const source = FakeEventSource.instances[0]
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1' }))

    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.runs()).toHaveLength(1)
    expect(store.runs()[0]?.id).toBe('wf-1')
  })

  it('preserves enrichment for surviving workflows across SSE refreshes (stable selection)', () => {
    // First load: wf-1 gets enriched with attempts
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
            status: 'running',
            caseId: 'case-1',
          },
        ],
      }
    )
    expect(store.session('wf-1')?.phase.caseId).toBe('case-1')

    // SSE invalidation triggers a reload
    const source = FakeEventSource.instances[0]
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1' }))

    // The reload returns the same workflow (wf-1 survived)
    // Enrichment requests are fired again, but BEFORE they complete the session
    // must still carry the previously enriched data (not wiped to projection-only).
    const activeReq = http.expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active')
    const removedReq = http.expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed')
    activeReq.flush({ data: { namespaceId: '', state: 'active', items: [snapshot] } })
    removedReq.flush({ data: { namespaceId: '', state: 'removed', items: [] } })
    http.expectOne((r) => r.url === '/api/namespaces').flush({ data: [] })

    // Immediately after the reload (before new enrichment arrives), the session
    // must still reflect the previous enrichment (caseId from attempt preserved).
    expect(store.session('wf-1')?.phase.caseId).toBe('case-1')

    // Complete the new enrichment requests without starting another cycle.
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: { items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/agent-questions')).flush({ data: [] })
  })

  it('purges enrichment for workflows that disappear from the snapshot set', () => {
    // Load two workflows
    const snap2 = snapshotFor('wf-2', 'ns-1', 'Second workflow')
    flushInitialWorkflows([snapshot, snap2])
    flushEnrichment()
    store.clearSessionSelection('wf-1')

    expect(store.runs()).toHaveLength(2)

    // SSE reload: wf-2 has been removed (disappears from active AND removed)
    const source = FakeEventSource.instances[0]
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1' }))

    flushInitialWorkflows([snapshot]) // only wf-1 survives

    expect(store.runs()).toHaveLength(1)
    expect(store.runs()[0]?.id).toBe('wf-1')
  })

  it('degrades to an empty list (never the mock fleet) when the REST backend fails', () => {
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active')
      .flush({ error: { code: 'UNAVAILABLE' } }, { status: 503, statusText: 'Service Unavailable' })
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed')
      .flush({ error: { code: 'UNAVAILABLE' } }, { status: 503, statusText: 'Service Unavailable' })
    flushNamespaces()

    expect(store.runs()).toEqual([])
    expect(store.activeRuns()).toEqual([])
    expect(store.workstreams()).toEqual([])
    expect(store.costs()).toEqual({ active: 0, workflowsUsd: 0, totalUsd: 0, unknownCostCount: 0 })
    expect(store.session('872641a8').id).toBe('872641a8')
  })

  it('does not fabricate a demo session for an unknown real run id', () => {
    flushInitialWorkflows()
    expect(store.session('unknown-run')).toBeUndefined()
  })

  // ---------------------------------------------------------------------------
  // Governed actions & blockers
  // ---------------------------------------------------------------------------

  it('preserves agent questions when later enrichment responses merge', () => {
    flushInitialWorkflows([snapshot])
    store.selectSession('wf-1')

    http
      .expectOne((r) => r.url.endsWith('/wf-1/agent-questions'))
      .flush({
        data: [
          {
            questionEventId: 'q-1',
            caseId: 'case-1',
            attemptId: 'a-1',
            stepId: 'build',
            question: 'Choose',
            questionType: 'SINGLE_CHOICE',
            options: ['A', 'B'],
            answered: false,
          },
        ],
      })
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: { items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })

    expect(store.session('wf-1')?.agentQuestions?.map((question) => question.questionEventId)).toEqual(['q-1'])
  })

  it('keeps Case metadata when question projection is unavailable', () => {
    flushInitialWorkflows([snapshot])
    store.selectSession('wf-1')
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: {} })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http.expectOne((r) => r.url.endsWith('/wf-1/actions')).flush({ data: { allowedActions: [], blockers: [] } })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/agent-questions'))
      .flush(
        {
          error: {
            code: 'AGENT_QUESTIONS_UNAVAILABLE',
            message: 'AgentOS unavailable',
            details: { caseId: 'case-9', namespaceId: 'ns-1', workflowId: 'wf-1', stepId: 'build' },
          },
        },
        { status: 503, statusText: 'Unavailable' }
      )

    expect(store.session('wf-1')?.agentQuestionsError).toEqual(
      expect.objectContaining({ code: 'AGENT_QUESTIONS_UNAVAILABLE', caseId: 'case-9', namespaceId: 'ns-1' })
    )
  })

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
    store.selectSession('wf-1')
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: { workflowId: 'wf-1' } })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/attempts')).flush({ data: [] })
    http
      .expectOne((r) => r.url.endsWith('/wf-1/actions'))
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    http.expectOne((r) => r.url.endsWith('/wf-1/agent-questions')).flush({ data: [] })

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
    request.flush({ data: { ok: true } })

    flushRunEnrichment('wf-1', false)
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

    flushRunEnrichment('wf-1', false)
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

    flushRunEnrichment('wf-1', false)
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

    flushRunEnrichment('wf-1', false)
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

    flushRunEnrichment('wf-1', false)
    flushInitialWorkflows([])
  })

  // ---------------------------------------------------------------------------
  // Removed workflows → destroyed runs
  // ---------------------------------------------------------------------------

  it('merges active and removed workflows, mapping removed ones to destroyed runs', () => {
    const removed = {
      ...snapshot,
      workflowId: 'wf-removed',
      relations: { rootWorkflowId: 'wf-removed', ticket: 'ABC-9' },
      projection: { ...snapshot.projection, title: 'Removed workflow', status: 'removed' },
    }
    flushInitialWorkflows([snapshot], [removed])
    flushEnrichment()

    expect(store.runs()).toHaveLength(2)
    expect(store.activeRuns()).toHaveLength(1)
    expect(store.destroyedRuns()).toHaveLength(1)
    const [destroyed] = store.destroyedRuns()
    expect(destroyed?.title).toBe('Removed workflow')
    expect(destroyed?.status).toBe('destroyed')
    expect(store.visibleRuns()).toHaveLength(store.activeRuns().length)
    store.showDestroyed.set(true)
    expect(store.visibleRuns()).toHaveLength(2)
  })

  it('keeps active runs when the removed fetch fails (partial degradation)', () => {
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'active')
      .flush({ data: { namespaceId: '', state: 'active', items: [snapshot] } })
    http
      .expectOne((r) => r.url === '/api/factory/workflows' && r.params.get('state') === 'removed')
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })
    flushNamespaces()
    flushEnrichment()

    expect(store.activeRuns()).toHaveLength(1)
    expect(store.destroyedRuns()).toEqual([])
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

    flushRunEnrichment('wf-1', false)
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

    flushRunEnrichment('wf-1', false)
    flushInitialWorkflows([])
  })

  it('stop does nothing (never fabricates) when no active attempt is resolvable', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.stop('wf-1')

    http.expectNone((r) => r.url.includes('/cancel'))
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

    flushRunEnrichment('wf-1', false)
    flushInitialWorkflows([])
  })

  it('remove degrades silently on failure', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    store.remove('wf-1')

    http
      .expectOne((r) => r.url === '/api/factory/workflows/wf-1')
      .flush({ error: { code: 'BOOM' } }, { status: 500, statusText: 'Server Error' })

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

    flushRunEnrichment('wf-1', false)
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
