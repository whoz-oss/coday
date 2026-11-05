import { inject, Injectable, NgZone } from '@angular/core'
import { Router } from '@angular/router'
import { TabCoordinatorService } from './tab-coordinator.service'

/**
 * Handles navigation from the companion PiP window back to the correct browser tab.
 *
 * The PiP window shares the same browsing context as the tab that opened it:
 * - BroadcastChannel messages from the PiP reach OTHER tabs but NOT the originating tab.
 * - The originating tab's TabCoordinatorService instance IS reachable directly via DI
 *   (same Angular injector), so we use findTabForNamespace() to detect it.
 *
 * Strategy:
 * 1. Find which tab owns the namespace via TabCoordinatorService.findTabForNamespace().
 *    This includes the current tab (self-registered in knownTabs by setup()).
 * 2. If it's the current tab → navigate locally (router + focus).
 * 3. If it's another tab → broadcast NAVIGATE_REQUEST, that tab handles it.
 * 4. If no tab owns the namespace → navigate locally as fallback.
 */
@Injectable({ providedIn: 'root' })
export class CompanionNavigationService {
  private readonly router = inject(Router)
  private readonly zone = inject(NgZone)
  private readonly tabCoordinator = inject(TabCoordinatorService)

  navigateTo(namespaceId: string, caseId: string): void {
    const ownerTabId = this.tabCoordinator.findTabForNamespace(namespaceId)

    if (!ownerTabId || this.tabCoordinator.isCurrentTab(ownerTabId)) {
      // Current tab owns the namespace, or no tab known — navigate locally
      this.zone.run(() => {
        void this.router.navigate(['/agentos/home'], {
          queryParams: { ns: namespaceId, case: caseId },
        })
        window.focus()
      })
    } else {
      // Another tab owns this namespace — broadcast, it will navigate and focus
      this.tabCoordinator.broadcastNavigate(namespaceId, caseId)
    }
  }
}
