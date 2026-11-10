import { effect, inject, Injectable, NgZone, OnDestroy, signal } from '@angular/core'
import { Router } from '@angular/router'
import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'

// ---------------------------------------------------------------------------
// Message protocol
// ---------------------------------------------------------------------------

interface TabAnnounceMessage {
  type: 'TAB_ANNOUNCE'
  tabId: string
  namespaceId: string | null
}

interface NavigateRequestMessage {
  type: 'NAVIGATE_REQUEST'
  namespaceId: string
  caseId: string
}

/** A global-stream event, relayed by the owning tab to every other tab. */
interface CaseEventMessage {
  type: 'CASE_EVENT'
  event: CaseEvent
}

type TabMessage = TabAnnounceMessage | NavigateRequestMessage | CaseEventMessage

/** Web Lock naming the tab that holds the global case-event stream for the whole browser. */
const STREAM_OWNER_LOCK = 'agentos-companion-stream'

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * Coordinates navigation across browser tabs via BroadcastChannel.
 *
 * Each tab registers itself with its current namespace. When the companion
 * wants to open a case, it broadcasts a NAVIGATE_REQUEST. The tab that owns
 * that namespace handles the navigation and calls window.focus().
 *
 * If no tab owns the namespace (e.g. it was closed), CompanionNavigationService
 * falls back to navigating in the current tab.
 *
 * Cross-tab coordination is an enhancement, not a requirement: where BroadcastChannel
 * is unavailable (older Safari, some webviews, and the jsdom/Node environment used by
 * the specs) the service stays fully functional in single-tab mode. It still registers
 * its own tab in knownTabs, so findTabForNamespace() resolves to the current tab and
 * CompanionNavigationService navigates locally — the same path it already takes when no
 * other tab claims the namespace. Only the fan-out to *other* tabs is lost.
 *
 * The active namespace is read from NamespaceStateService, the app-wide source already
 * kept in sync with the `?ns` query param (and already read this way by
 * ShellTopbarComponent). No component has to hand it over, so no component owns this
 * service's startup — start() is called once at app startup, not from a view.
 */
@Injectable({ providedIn: 'root' })
export class TabCoordinatorService implements OnDestroy {
  private readonly router = inject(Router)
  private readonly zone = inject(NgZone)
  private readonly namespaceState = inject(NamespaceStateService)

  /**
   * Null where the platform has no BroadcastChannel — see the class doc's degraded mode.
   *
   * `-v2`: builds before #1606 answer every announce, so their tabs bounce messages among themselves until reloaded,
   * which a server restart does not do. A new name keeps that traffic away from the tabs of this build.
   */
  private readonly channel: BroadcastChannel | null =
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('agentos-companion-v2')

  readonly tabId = crypto.randomUUID()

  /** Namespace currently active in this tab, mirrored from NamespaceStateService. */
  readonly currentNamespaceId = signal<string | null>(null)

  private started = false

  /** Known tabs and their namespaces, maintained by listening to TAB_ANNOUNCE. */
  private readonly knownTabs = signal<Map<string, string | null>>(new Map())

  /**
   * True when this browser can both elect an owner for the global case-event stream
   * (Web Locks) and relay that stream to the other tabs (BroadcastChannel). Where either
   * is missing, every tab opens its own stream — correct, just one connection per tab.
   */
  readonly coordinated: boolean = this.channel !== null && typeof navigator !== 'undefined' && 'locks' in navigator

  /** Whether this tab is the one holding the global case-event stream. */
  readonly isStreamOwner = signal(false)

  private readonly caseEventHandlers = new Set<(event: CaseEvent) => void>()
  private streamOwnership: Promise<void> | null = null

  /**
   * Resolves once this tab owns the global case-event stream, and stays pending in the tabs
   * that do not. A browser allows only 6 HTTP/1.1 connections per origin, shared by every
   * tab, and the app needs several of them for its per-case streams: one global stream for
   * the whole browser instead of one per tab is what keeps that budget workable (#1616).
   *
   * Ownership is a Web Lock held for the lifetime of the tab, never a flag: the browser
   * releases it when the tab goes away, which hands the stream to the next waiting tab
   * without any of them having to notice the first one died.
   *
   * A lock claim that fails outright (an inactive document, an embedded context) resolves
   * too: a failed election must leave the caller with a stream of its own, not waiting for
   * one nobody will open.
   */
  whenStreamOwner(): Promise<void> {
    if (!this.coordinated) return Promise.resolve()
    this.streamOwnership ??= new Promise<void>((resolve) => {
      navigator.locks
        .request(STREAM_OWNER_LOCK, () => {
          this.isStreamOwner.set(true)
          resolve()
          // Never resolves: returning here would release the lock and let a second tab
          // open the same stream.
          return new Promise<void>(() => undefined)
        })
        .catch((err) => {
          console.warn('[TabCoordinator] could not claim the stream ownership lock', err)
          resolve()
        })
    })
    return this.streamOwnership
  }

  /**
   * Relay an event from the owning tab to the others.
   *
   * Only the owner may call it: where no owner is elected every tab reads the stream for
   * itself, and relaying on top would have each tab handle every event once per open tab.
   */
  broadcastCaseEvent(event: CaseEvent): void {
    if (!this.isStreamOwner()) return
    const msg: CaseEventMessage = { type: 'CASE_EVENT', event }
    this.channel?.postMessage(msg)
  }

  /** Register a handler for the events relayed by the owning tab; call the result to drop it. */
  onCaseEvent(handler: (event: CaseEvent) => void): () => void {
    this.caseEventHandlers.add(handler)
    return () => this.caseEventHandlers.delete(handler)
  }

  /**
   * Returns the tabId that owns a given namespaceId, or null if none is known.
   * Used by CompanionNavigationService to decide whether to broadcast or navigate locally.
   */
  findTabForNamespace(namespaceId: string): string | null {
    for (const [tabId, ns] of this.knownTabs()) {
      if (ns === namespaceId) return tabId
    }
    return null
  }

  /**
   * Returns true if the current tab owns the given namespace.
   */
  isCurrentTab(tabId: string): boolean {
    return tabId === this.tabId
  }

  /**
   * Broadcast a navigation request to all tabs.
   * The tab that owns namespaceId will handle it.
   */
  broadcastNavigate(namespaceId: string, caseId: string): void {
    const msg: NavigateRequestMessage = { type: 'NAVIGATE_REQUEST', namespaceId, caseId }
    this.channel?.postMessage(msg)
  }

  /**
   * Start listening for messages and announcing this tab's namespace.
   * Idempotent — safe to call more than once.
   *
   * Must run in an injection context (the effect below). Called once at app startup
   * alongside CompanionStateService.startSse().
   */
  start(): void {
    if (this.started) return
    this.started = true

    // No channel: skip the listener entirely. The effect below still runs, so this tab
    // registers itself and single-tab navigation keeps working.
    if (this.channel) {
      this.channel.onmessage = (event: MessageEvent<TabMessage>) => this.onMessage(event.data)
    }

    // Keep knownTabs up to date with our own namespace too, so findTabForNamespace() can
    // match the current tab without relying on BroadcastChannel (which doesn't deliver to
    // the sender's own context). Re-announces on every namespace change so other tabs
    // stay in sync.
    effect(() => {
      const ns = this.namespaceState.activeNamespaceId()
      this.currentNamespaceId.set(ns)
      this.knownTabs.update((m) => new Map(m).set(this.tabId, ns))
      this.announce()
    })
  }

  private onMessage(msg: TabMessage): void {
    switch (msg.type) {
      case 'TAB_ANNOUNCE': {
        // Answer news only (a tab we did not know, or a known tab on another namespace) so that it learns about us.
        // Answering every announce made two tabs bounce messages forever, and three multiply them, pinning the CPU
        // (#1606).
        const known = this.knownTabs()
        if (known.has(msg.tabId) && known.get(msg.tabId) === msg.namespaceId) break
        this.knownTabs.update((m) => new Map(m).set(msg.tabId, msg.namespaceId))
        this.announce()
        break
      }

      case 'CASE_EVENT':
        // Back into the zone: BroadcastChannel delivery is not patched by zone.js, and the
        // handlers write the signals the views read.
        this.zone.run(() => this.caseEventHandlers.forEach((handler) => handler(msg.event)))
        break

      case 'NAVIGATE_REQUEST':
        if (msg.namespaceId === this.currentNamespaceId()) {
          this.zone.run(() => {
            void this.router.navigate(['/agentos/home'], {
              queryParams: { ns: msg.namespaceId, case: msg.caseId },
            })
            window.focus()
          })
        }
        break
    }
  }

  private announce(): void {
    const msg: TabAnnounceMessage = {
      type: 'TAB_ANNOUNCE',
      tabId: this.tabId,
      namespaceId: this.currentNamespaceId(),
    }
    this.channel?.postMessage(msg)
  }

  ngOnDestroy(): void {
    this.channel?.close()
    this.caseEventHandlers.clear()
  }
}
