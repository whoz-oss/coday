import { NgZone } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Configuration, NamespaceCaseInvalidationSseService } from '@whoz-oss/agentos-api-client'

class FakeEventSource {
  static latest: FakeEventSource | null = null
  readonly listeners = new Map<string, EventListener>()
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  closed = false

  constructor(readonly url: string) {
    FakeEventSource.latest = this
  }

  addEventListener(type: string, listener: EventListener): void {
    this.listeners.set(type, listener)
  }

  close(): void {
    this.closed = true
  }
}

describe('NamespaceCaseInvalidationSseService', () => {
  const OriginalEventSource = globalThis.EventSource

  beforeEach(() => {
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource
    TestBed.configureTestingModule({
      providers: [
        NamespaceCaseInvalidationSseService,
        { provide: Configuration, useValue: { basePath: '/api/agentos' } },
        {
          provide: NgZone,
          useValue: {
            runOutsideAngular: (callback: () => unknown) => callback(),
            run: (callback: () => unknown) => callback(),
          },
        },
      ],
    })
  })

  afterEach(() => {
    globalThis.EventSource = OriginalEventSource
    FakeEventSource.latest = null
    TestBed.resetTestingModule()
  })

  it('emits on open/reconnect and payload-free named invalidations, then closes', () => {
    const service = TestBed.inject(NamespaceCaseInvalidationSseService)
    const received: string[] = []
    const subscription = service.connect('ns/one').subscribe((signal) => received.push(signal))
    const source = FakeEventSource.latest!

    expect(source.url).toBe('/api/agentos/api/namespaces/ns/one/case-invalidation')
    expect(source.listeners.has('namespace-case-invalidation')).toBe(true)

    source.onopen?.(new Event('open'))
    source.listeners.get('namespace-case-invalidation')?.(new MessageEvent('namespace-case-invalidation', { data: '' }))
    source.onerror?.(new Event('error'))
    source.onopen?.(new Event('open'))

    expect(received).toEqual(['connected', 'invalidated', 'connected'])
    subscription.unsubscribe()
    expect(source.closed).toBe(true)
  })
})
