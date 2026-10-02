import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { TestBed } from '@angular/core/testing'
import { FactoryStore } from './factory.store'
import { SANDBOXES } from './mock-data'
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

  function flushInitialWorkflows(items: unknown[] = []): void {
    http
      .expectOne((r) => r.url === '/api/factory/workflows')
      .flush({ data: { namespaceId: '', state: 'active', items } })
  }

  function flushEnrichment(
    metrics: unknown = { workflowId: 'wf-1' },
    interactions: unknown = { workflowId: 'wf-1', items: [] }
  ): void {
    http.expectOne((r) => r.url.endsWith('/wf-1/timing')).flush({ data: { workflowId: 'wf-1', startedAt } })
    http.expectOne((r) => r.url.endsWith('/wf-1/evidence')).flush({ data: { workflowId: 'wf-1', items: [] } })
    http.expectOne((r) => r.url.endsWith('/wf-1/metrics')).flush({ data: metrics })
    http.expectOne((r) => r.url.endsWith('/wf-1/interactions')).flush({ data: interactions })
  }

  it('exposes the mock sandboxes before/without real data', () => {
    flushInitialWorkflows()
    expect(store.sandboxes()).toEqual(SANDBOXES)
  })

  it('hides destroyed sandboxes from the active list', () => {
    flushInitialWorkflows()
    expect(store.activeSandboxes()).toHaveLength(2)
    expect(store.activeSandboxes().every((sandbox) => sandbox.status !== 'destroyed')).toBe(true)
  })

  it('switches between active and visible sandboxes with showDestroyed', () => {
    flushInitialWorkflows()
    expect(store.visibleSandboxes()).toEqual(store.activeSandboxes())

    store.showDestroyed.set(true)
    expect(store.visibleSandboxes()).toEqual(store.sandboxes())
  })

  it('aggregates the workflow costs of the active sandboxes', () => {
    flushInitialWorkflows()
    const expected = store.activeSandboxes().reduce((sum, sandbox) => sum + (sandbox.run?.costUsd ?? 0), 0)

    expect(store.costs().workflowsUsd).toBeCloseTo(expected, 6)
    expect(store.costs().active).toBe(store.activeSandboxes().length)
  })

  it('marks a sandbox as destroyed and drops its run', () => {
    flushInitialWorkflows()
    const target = store.activeSandboxes()[0]
    if (!target) throw new Error('expected at least one active sandbox')

    store.destroy(target.name)

    const updated = store.sandboxes().find((sandbox) => sandbox.name === target.name)
    expect(updated?.status).toBe('destroyed')
    expect(updated?.run).toBeUndefined()
    expect(updated?.finalCostUsd).toBe(target.run?.costUsd ?? 0)
  })

  it('maps real workflow projections onto the active sandboxes', () => {
    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.activeSandboxes()[0]?.run?.id).toBe('wf-1')
    expect(store.activeSandboxes()[0]?.run?.status).toBe('running')
    // Extra active sandboxes keep their mock run when there is no real match.
    expect(store.activeSandboxes()[1]?.run?.id).toBe('c5f49c91')
  })

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
    expect(store.costs().workflowsUsd).toBeCloseTo(
      store.activeSandboxes().reduce((sum, sandbox) => sum + (sandbox.run?.costUsd ?? 0), 0),
      6
    )
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

    const session = store.session('wf-1')
    expect(session?.id).toBe('wf-1')
    expect(session?.interactions).toEqual([])
    // The other enrichments still landed despite the failed interaction fetch.
    expect(session?.workflow).toBe('Real workflow')
  })

  it('refetches the workflow list on an SSE invalidation', () => {
    flushInitialWorkflows([])

    const source = FakeEventSource.instances[0]
    source?.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1' }))

    flushInitialWorkflows([snapshot])
    flushEnrichment()

    expect(store.activeSandboxes()[0]?.run?.id).toBe('wf-1')
  })

  it('degrades gracefully when the REST backend fails', () => {
    http
      .expectOne((r) => r.url === '/api/factory/workflows')
      .flush({ error: { code: 'UNAVAILABLE' } }, { status: 503, statusText: 'Service Unavailable' })

    expect(store.activeSandboxes()).toHaveLength(2)
    expect(store.activeSandboxes()[0]?.run?.id).toBe('872641a8')
    expect(store.session('872641a8').id).toBe('872641a8')
  })

  it('falls back to the demo session for an unknown run id', () => {
    flushInitialWorkflows()
    const session = store.session('unknown-run')
    expect(session?.id).toBe('unknown-run')
  })
})
