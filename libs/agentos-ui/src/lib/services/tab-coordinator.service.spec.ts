import { createEnvironmentInjector, EnvironmentInjector, runInInjectionContext, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { CaseEvent } from '@whoz-oss/agentos-api-client'
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

/** The lock the service claims, spelled out here as the service keeps it private. */
const STREAM_OWNER_LOCK = 'agentos-companion-stream'

/**
 * In-memory Web Locks: one holder per name, the other claims queued in order, and a lock only
 * ever released by its holder's tab going away — exactly how the app uses them.
 */
class FakeLockManager {
  /** Set to make every claim fail, as an inactive document or an embedded context does. */
  rejectWith: Error | null = null
  private readonly held = new Set<string>()
  private readonly queues = new Map<string, Array<() => void>>()

  async request(name: string, callback: () => Promise<void>): Promise<void> {
    if (this.rejectWith) throw this.rejectWith
    if (this.held.has(name)) {
      await new Promise<void>((resolve) => {
        const queue = this.queues.get(name) ?? []
        queue.push(resolve)
        this.queues.set(name, queue)
      })
    }
    this.held.add(name)
    await callback()
  }

  /** The holder's tab goes away: the browser releases its lock and hands it to the next claim. */
  closeHolder(name: string): void {
    this.held.delete(name)
    this.queues.get(name)?.shift()?.()
  }
}

/** Lets the pending lock claims and `whenStreamOwner` continuations run. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

interface Tab {
  service: TabCoordinatorService
  namespace: ReturnType<typeof signal<string | null>>
}

describe('TabCoordinatorService', () => {
  const originalBroadcastChannel = (globalThis as { BroadcastChannel?: unknown }).BroadcastChannel
  /** The injectors of the tabs a test opened: destroyed after it, which closes their channels and stops their effects. */
  let tabInjectors: EnvironmentInjector[]
  const originalLocks = Object.getOwnPropertyDescriptor(navigator, 'locks')
  let locks: FakeLockManager

  beforeEach(() => {
    bus = new FakeBus()
    tabInjectors = []
    locks = new FakeLockManager()
    ;(globalThis as { BroadcastChannel?: unknown }).BroadcastChannel = FakeBroadcastChannel
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: { navigate: jest.fn() } }] })
  })

  afterEach(() => {
    for (const injector of tabInjectors) injector.destroy()
    TestBed.resetTestingModule()
    ;(globalThis as { BroadcastChannel?: unknown }).BroadcastChannel = originalBroadcastChannel
    if (originalLocks) Object.defineProperty(navigator, 'locks', originalLocks)
    else Reflect.deleteProperty(navigator, 'locks')
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

  // ---------------------------------------------------------------------------
  // Global stream ownership — one connection per browser, not per tab (#1616)
  // ---------------------------------------------------------------------------

  const caseEvent = { id: 'e-1', type: 'ErrorEvent', caseId: 'c-1', namespaceId: 'ns-a' } as unknown as CaseEvent

  it('gives the global stream to one tab, and to the next one when that tab goes away', async () => {
    const a = openTab('ns-a')
    const b = openTab('ns-b')
    let bOwnsTheStream = false

    await a.service.whenStreamOwner()
    void b.service.whenStreamOwner().then(() => (bOwnsTheStream = true))
    await settle()

    expect(a.service.isStreamOwner()).toBe(true)
    expect(bOwnsTheStream).toBe(false)
    expect(b.service.isStreamOwner()).toBe(false)

    locks.closeHolder(STREAM_OWNER_LOCK)
    await settle()

    expect(bOwnsTheStream).toBe(true)
    expect(b.service.isStreamOwner()).toBe(true)
  })

  it('relays a case event to the other tabs, never to the tab that owns the stream', async () => {
    const owner = openTab('ns-a')
    const other = openTab('ns-b')
    const heardByOwner = jest.fn()
    const heardByOther = jest.fn()
    owner.service.onCaseEvent(heardByOwner)
    other.service.onCaseEvent(heardByOther)
    await owner.service.whenStreamOwner()

    owner.service.broadcastCaseEvent(caseEvent)
    bus.flush()

    expect(heardByOther).toHaveBeenCalledWith(caseEvent)
    expect(heardByOwner).not.toHaveBeenCalled()
  })

  it('relays nothing from a tab that does not own the stream', () => {
    const notOwner = openTab('ns-a')
    const other = openTab('ns-b')
    const heardByOther = jest.fn()
    other.service.onCaseEvent(heardByOther)

    notOwner.service.broadcastCaseEvent(caseEvent)
    bus.flush()

    // Without an owner every tab reads the stream for itself; relaying on top of that would
    // have each tab handle every event once per open tab.
    expect(heardByOther).not.toHaveBeenCalled()
  })

  it('drops a handler once its unsubscribe is called', async () => {
    const owner = openTab('ns-a')
    const other = openTab('ns-b')
    const heard = jest.fn()
    const unsubscribe = other.service.onCaseEvent(heard)
    await owner.service.whenStreamOwner()

    unsubscribe()
    owner.service.broadcastCaseEvent(caseEvent)
    bus.flush()

    expect(heard).not.toHaveBeenCalled()
  })

  it('leaves a tab to its own stream when the lock claim fails outright', async () => {
    locks.rejectWith = new Error('document is not fully active')
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined)
    const a = openTab('ns-a')

    // Resolving is the point: a claim that failed must not leave the caller waiting for a
    // stream nobody will open.
    await expect(a.service.whenStreamOwner()).resolves.toBeUndefined()

    expect(a.service.isStreamOwner()).toBe(false)
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('leaves every tab to its own stream where Web Locks are missing', async () => {
    Reflect.deleteProperty(navigator, 'locks')

    const a = openTab('ns-a')

    expect(a.service.coordinated).toBe(false)
    await expect(a.service.whenStreamOwner()).resolves.toBeUndefined()
  })

  it('leaves every tab to its own stream where BroadcastChannel is missing', async () => {
    ;(globalThis as { BroadcastChannel?: unknown }).BroadcastChannel = undefined

    const a = openTab('ns-a')

    expect(a.service.coordinated).toBe(false)
    await expect(a.service.whenStreamOwner()).resolves.toBeUndefined()
  })
})
