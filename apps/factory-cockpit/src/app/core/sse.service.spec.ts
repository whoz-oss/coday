import { TestBed } from '@angular/core/testing'
import { EVENT_SOURCE_FACTORY, SseInvalidation, SseService } from './sse.service'

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

  open(): void {
    this.onopen?.(new Event('open'))
  }

  fail(): void {
    this.onerror?.(new Event('error'))
  }

  emit(type: string, data: string): void {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data } as MessageEvent)
    }
  }
}

describe('SseService', () => {
  let service: SseService

  beforeEach(() => {
    FakeEventSource.instances = []
    jest.useFakeTimers()
    TestBed.configureTestingModule({
      providers: [
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => new FakeEventSource(url) as unknown as EventSource,
        },
      ],
    })
    service = TestBed.inject(SseService)
  })

  afterEach(() => jest.useRealTimers())

  it('connects to the base stream and subscribes to every named event', () => {
    service.connect()

    expect(FakeEventSource.instances).toHaveLength(1)
    const source = FakeEventSource.instances[0]
    expect(source.url).toBe('/api/factory/workflows/stream')

    const received: SseInvalidation[] = []
    service.invalidations$.subscribe((event) => received.push(event))

    source.emit('workflow-projection-updated', JSON.stringify({ workflowId: 'wf-1', namespaceId: 'ns-1', revision: 3 }))
    source.emit('workflow-projection-removed', JSON.stringify({ workflowId: 'wf-2' }))
    source.emit('workflow-projection-restored', JSON.stringify({ workflowId: 'wf-3' }))
    source.emit('workflow-projection-purged', JSON.stringify({ workflowId: 'wf-4' }))

    expect(received.map((event) => event.type)).toEqual([
      'workflow-projection-updated',
      'workflow-projection-removed',
      'workflow-projection-restored',
      'workflow-projection-purged',
    ])
    expect(received[0]).toMatchObject({ workflowId: 'wf-1', namespaceId: 'ns-1', revision: 3 })
  })

  it('appends the namespace filter only when provided', () => {
    service.connect('ns-7')
    expect(FakeEventSource.instances[0].url).toBe('/api/factory/workflows/stream?namespaceId=ns-7')
  })

  it('tolerates non-JSON payloads', () => {
    service.connect()
    const received: SseInvalidation[] = []
    service.invalidations$.subscribe((event) => received.push(event))

    FakeEventSource.instances[0].emit('workflow-projection-purged', 'not-json')

    expect(received).toHaveLength(1)
    expect(received[0].data).toBe('not-json')
    expect(received[0].workflowId).toBeUndefined()
  })

  it('reconnects after a drop and emits reconnected only once the stream is back', () => {
    const reconnects: number[] = []
    service.reconnected$.subscribe(() => reconnects.push(1))

    service.connect()
    const first = FakeEventSource.instances[0]
    first.open()
    expect(reconnects).toHaveLength(0)

    first.fail()
    expect(FakeEventSource.instances).toHaveLength(1)

    jest.advanceTimersByTime(2000)
    expect(FakeEventSource.instances).toHaveLength(2)

    FakeEventSource.instances[1].open()
    expect(reconnects).toHaveLength(1)
  })

  it('cancels any pending reconnect on close', () => {
    service.connect()
    FakeEventSource.instances[0].fail()

    service.close()
    jest.advanceTimersByTime(60_000)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0].closed).toBe(true)
  })
})
