import { Injector, NgZone, runInInjectionContext } from '@angular/core'
import { Configuration, NamespaceCaseListUpdateSseService } from '@whoz-oss/agentos-api-client'

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

describe('NamespaceCaseListUpdateSseService', () => {
  const OriginalEventSource = globalThis.EventSource

  beforeEach(() => {
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource
  })

  afterEach(() => {
    globalThis.EventSource = OriginalEventSource
    FakeEventSource.latest = null
  })

  it('emits on open/reconnect and named updates with a neutral payload, then closes', () => {
    const injector = Injector.create({
      providers: [
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
    const service = runInInjectionContext(injector, () => new NamespaceCaseListUpdateSseService())
    const received: string[] = []
    const subscription = service.connect('ns/one').subscribe((signal) => received.push(signal))
    const source = FakeEventSource.latest!

    expect(source.url).toBe('/api/agentos/api/namespaces/ns%2Fone/case-list-updates')
    expect(source.listeners.has('namespace-case-list-updated')).toBe(true)

    source.onopen?.(new Event('open'))
    source.listeners.get('namespace-case-list-updated')?.(new MessageEvent('namespace-case-list-updated', { data: '' }))
    source.onerror?.(new Event('error'))
    source.onopen?.(new Event('open'))

    expect(received).toEqual(['connected', 'updated', 'connected'])
    subscription.unsubscribe()
    expect(source.closed).toBe(true)
  })
})
