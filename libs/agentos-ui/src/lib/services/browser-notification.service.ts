import { Injectable, NgZone, inject, signal } from '@angular/core'
import { MatSnackBar } from '@angular/material/snack-bar'
import { CompanionNavigationService } from './companion-navigation.service'
import { CompanionNotification } from './companion-state.service'

const STORAGE_KEY = 'agentos.notifications.enabled'

/**
 * Manages OS / browser notifications via the standard Web Notifications API (`window.Notification`).
 *
 * Used by `CompanionStateService` when the Companion PiP window is closed (`!companionPip.isOpen()`):
 * - Persists the user's on/off preference in `localStorage`.
 * - If browser permission is already `'granted'` and the user hasn't explicitly disabled it,
 *   defaults to enabled (`true`).
 * - Clicking a notification navigates to and focuses the tab owning the root case via
 *   `CompanionNavigationService`.
 * - Tracks active `Notification` instances by notification id so they are automatically closed
 *   when the question/confirmation is answered or dismissed in the UI.
 */
@Injectable({ providedIn: 'root' })
export class BrowserNotificationService {
  private readonly zone = inject(NgZone)
  private readonly snackBar = inject(MatSnackBar)
  private readonly companionNav = inject(CompanionNavigationService)

  private readonly activeNotifications = new Map<string, Notification>()

  /** Whether OS browser notifications are currently enabled. */
  readonly enabled = signal<boolean>(this.readInitialState())

  isSupported(): boolean {
    return typeof window !== 'undefined' && 'Notification' in window
  }

  /**
   * Toggle browser notifications on/off.
   * Must be called from a user gesture (click handler) so `Notification.requestPermission()`
   * is allowed by the browser.
   */
  async toggle(): Promise<void> {
    if (!this.isSupported()) {
      this.snackBar.open('Browser notifications are not supported in this environment.', 'Dismiss', { duration: 4000 })
      return
    }

    if (this.enabled()) {
      this.enabled.set(false)
      this.writeStorage(false)
      this.closeAll()
      return
    }

    let permission = Notification.permission
    if (permission === 'default') {
      try {
        permission = await Notification.requestPermission()
      } catch (err) {
        console.warn('[BrowserNotification] permission request failed', err)
        return
      }
    }

    if (permission !== 'granted') {
      this.enabled.set(false)
      this.writeStorage(false)
      this.snackBar.open(
        'Browser notifications are blocked. Allow notifications for this site in your browser settings.',
        'Dismiss',
        { duration: 5000 }
      )
      return
    }

    this.enabled.set(true)
    this.writeStorage(true)
  }

  /**
   * Show a native browser notification for `n` under `caseTitle` (grouped under `rootCaseId`).
   */
  notify(rootCaseId: string, namespaceId: string, caseTitle: string, n: CompanionNotification): void {
    if (!this.isSupported() || !this.enabled() || Notification.permission !== 'granted') return

    // Avoid duplicate notifications for the same event id
    if (this.activeNotifications.has(n.id)) return

    const body = this.formatBody(n)
    try {
      const notification = new Notification(caseTitle, {
        body,
        tag: n.id,
        requireInteraction: n.blocking,
      })

      this.activeNotifications.set(n.id, notification)

      notification.onclick = () => {
        this.zone.run(() => {
          notification.close()
          this.activeNotifications.delete(n.id)
          this.companionNav.navigateTo(namespaceId, rootCaseId)
        })
      }

      notification.onclose = () => {
        this.activeNotifications.delete(n.id)
      }
    } catch (err) {
      console.warn('[BrowserNotification] failed to display notification', err)
    }
  }

  /**
   * Close a specific browser notification if it is still displayed (e.g. when answered or dismissed).
   */
  close(notifId: string): void {
    const existing = this.activeNotifications.get(notifId)
    if (existing) {
      this.activeNotifications.delete(notifId)
      try {
        existing.close()
      } catch {
        // Ignore close errors
      }
    }
  }

  /**
   * Close all active browser notifications for a given list of notification ids.
   */
  closeMany(notifIds: Iterable<string>): void {
    for (const id of notifIds) {
      this.close(id)
    }
  }

  private closeAll(): void {
    for (const [id, notif] of this.activeNotifications) {
      this.activeNotifications.delete(id)
      try {
        notif.close()
      } catch {
        // Ignore
      }
    }
  }

  private formatBody(n: CompanionNotification): string {
    switch (n.kind) {
      case 'confirmation':
        return `Confirmation needed — ${n.toolName}\n${n.inputPreview}`
      case 'question':
        return `${n.agentName} asks: ${n.question}`
      case 'finished':
        return `${n.agentName} finished`
      case 'error':
        return `Error: ${n.message}`
    }
  }

  private readInitialState(): boolean {
    if (!this.isSupported()) return false
    if (Notification.permission !== 'granted') return false
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      // If permission was already granted and user never explicitly turned it off, default to true
      return stored === null ? true : stored === 'true'
    } catch {
      return true
    }
  }

  private writeStorage(value: boolean): void {
    try {
      localStorage.setItem(STORAGE_KEY, String(value))
    } catch {
      // Ignore storage errors (private mode / quota)
    }
  }
}
