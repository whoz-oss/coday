import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { TabCoordinatorService } from './tab-coordinator.service'

/**
 * In-memory BroadcastChannel: like the real one, a message reaches every other open channel of the same name but not
 * the sender, and arrives later (queued until `flush`). Flushing by hand keeps the tests deterministic and lets them
 * see a loop as a growing queue instead of a hang.
 */
class FakeBus {
  readonly channels = new Set<FakeBroadcastChannel>()
  private readonly queue: { from: FakeBroadcastChannel; data: unknown }[] = []
  posted = 0

  post(from: FakeBroadcastChannel, data: unknown): void {
    this.posted++
    // A copy, as the real channel clones: the messages are flat objects (jsdom has no structuredClone).
    this.queue.push({ from, data: { ...(data as object) } })
  }

  /** Delivers until nothing is left; throws past `max` deliveries, which only an endless exchange reaches. */
  flush(max = 1_000): void {
    for (let delivered = 0; this.queue.length > 0; delivered++) {
      if (delivered >= max) throw new Error(`Still ${this.queue.length} messages queued after ${max} deliveries`)
      const { from, data } = this.queue.shift() as { from: FakeBroadcastChannel; data: unknown }
      for (const channel of this.channels) {
        if (channel !== from && channel.name === from.name) channel.onmessage?.({ data } as MessageEvent)
      }
    }
  }
}

let bus: FakeBus

class FakeBroadcastChannel {
  onmessage: ((event: MessageEvent) => void) | null = null

  constructor(readonly name: string) {
    bus.channels.add(this)
  }

  postMessage(data: unknown): void {
    bus.post(this, data)
  }

  close(): void {
    bus.channels.delete(this)
  }
}

interface Tab {
  service: TabCoordinatorService
  namespace: ReturnType<typeof signal<string | null>>
}

describe('TabCoordinatorService', () => {
  const originalBroadcastChannel = (globalThis as { BroadcastChannel?: unknown }).BroadcastChannel
  /** The injectors of the tabs a test opened: destroyed after it, which closes their channels and stops their effects. */
  let tabInjectors: EnvironmentInjector[]

  beforeEach(() => {
    bus = new FakeBus()
    tabInjectors = []
    ;(globalThis as { BroadcastChannel?: unknown }).BroadcastChannel = FakeBroadcastChannel
    TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: { navigate: jest.fn() } }] })
  })

  afterEach(() => {
    for (const injector of tabInjectors) injector.destroy()
    TestBed.resetTestingModule()
    ;(globalThis as { BroadcastChannel?: unknown }).BroadcastChannel = originalBroadcastChannel
  })

  /** One browser tab: its own coordinator and namespace state, started like app.config does it. */
  function openTab(namespaceId: string | null): Tab {
    const namespace = signal<string | null>(namespaceId)
    const injector = createEnvironmentInjector(
      [TabCoordinatorService, { provide: NamespaceStateService, useValue: { activeNamespaceId: namespace } }],
      TestBed.inject(EnvironmentInjector)
    )
    tabInjectors.push(injector)
    const service = injector.get(TabCoordinatorService)
    runInInjectionContext(injector, () => service.start())
    TestBed.flushEffects()
    return { service, namespace }
  }

  it('lets two tabs learn about each other, then stops talking', () => {
    const a = openTab('ns-a')
    const b = openTab('ns-b')

    bus.flush()

    expect(a.service.findTabForNamespace('ns-b')).toBe(b.service.tabId)
    expect(b.service.findTabForNamespace('ns-a')).toBe(a.service.tabId)
    // One announce per tab, then at most one answer per tab for each tab it learns about.
    expect(bus.posted).toBeLessThanOrEqual(2 * 2)
  })

  it('stops as well with three tabs, where answering every announce multiplied the messages', () => {
    const tabs = [openTab('ns-a'), openTab('ns-b'), openTab('ns-c')]

    bus.flush()

    for (const tab of tabs) {
      expect(tab.service.findTabForNamespace('ns-a')).toBe(tabs[0]?.service.tabId)
      expect(tab.service.findTabForNamespace('ns-b')).toBe(tabs[1]?.service.tabId)
      expect(tab.service.findTabForNamespace('ns-c')).toBe(tabs[2]?.service.tabId)
    }
    expect(bus.posted).toBeLessThanOrEqual(3 * 3)
  })

  it('lets a tab join tabs that already know each other, then stops talking', () => {
    const a = openTab('ns-a')
    const b = openTab('ns-b')
    bus.flush()
    const before = bus.posted

    const c = openTab('ns-c')
    bus.flush()

    expect(a.service.findTabForNamespace('ns-c')).toBe(c.service.tabId)
    expect(b.service.findTabForNamespace('ns-c')).toBe(c.service.tabId)
    expect(c.service.findTabForNamespace('ns-a')).toBe(a.service.tabId)
    expect(c.service.findTabForNamespace('ns-b')).toBe(b.service.tabId)
    // The newcomer's announce, an answer from each of the two tabs, and the newcomer's answer to each of them.
    expect(bus.posted - before).toBeLessThanOrEqual(1 + 2 + 2)
  })

  it('tells the other tabs when a tab switches namespace, and stops there', () => {
    const a = openTab('ns-a')
    const b = openTab('ns-b')
    bus.flush()
    const before = bus.posted

    a.namespace.set('ns-c')
    TestBed.flushEffects()
    bus.flush()

    expect(b.service.findTabForNamespace('ns-c')).toBe(a.service.tabId)
    expect(b.service.findTabForNamespace('ns-a')).toBeNull()
    // The announce of the switch, and the answer of the other tab.
    expect(bus.posted - before).toBeLessThanOrEqual(2)
  })

  it('stays apart from the tabs of builds before #1606, which answer every announce', () => {
    const olderBuild = new FakeBroadcastChannel('agentos-companion')
    const heardByOlderBuild = jest.fn()
    olderBuild.onmessage = heardByOlderBuild
    const a = openTab('ns-a')

    olderBuild.postMessage({ type: 'TAB_ANNOUNCE', tabId: 'older', namespaceId: 'ns-old' })
    bus.flush()

    expect(heardByOlderBuild).not.toHaveBeenCalled()
    expect(a.service.findTabForNamespace('ns-old')).toBeNull()
  })
})
