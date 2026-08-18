import { effect, inject, Injectable, NgZone, OnDestroy, signal } from '@angular/core'
import { Router } from '@angular/router'
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

type TabMessage = TabAnnounceMessage | NavigateRequestMessage

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

  /** Null where the platform has no BroadcastChannel — see the class doc's degraded mode. */
  private readonly channel: BroadcastChannel | null =
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('agentos-companion')

  readonly tabId = crypto.randomUUID()

  /** Namespace currently active in this tab, mirrored from NamespaceStateService. */
  readonly currentNamespaceId = signal<string | null>(null)

  private started = false

  /** Known tabs and their namespaces, maintained by listening to TAB_ANNOUNCE. */
  private readonly knownTabs = signal<Map<string, string | null>>(new Map())

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
      case 'TAB_ANNOUNCE':
        this.knownTabs.update((m) => new Map(m).set(msg.tabId, msg.namespaceId))
        // Re-announce so the new/updated tab knows about us too
        this.announce()
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
  }
}
