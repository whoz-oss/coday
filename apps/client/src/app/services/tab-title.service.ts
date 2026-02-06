import { inject, Injectable } from '@angular/core'
import { DOCUMENT } from '@angular/common'
import { combineLatest } from 'rxjs'
import { UnreadMessagesService } from './unread-messages.service'
import { ProjectStateService } from '../core/services/project-state.service'

@Injectable({
  providedIn: 'root',
})
export class TabTitleService {
  // Red dot notification emoji
  private readonly ATTENTION_EMOJI = '🔴'

  // System activity state
  private isSystemActive = false
  private systemActiveTimer: any = null
  private readonly SYSTEM_ACTIVE_TIMEOUT = 4000 // 4 seconds

  // Modern Angular dependency injection
  private readonly document = inject(DOCUMENT)
  private unreadService = inject(UnreadMessagesService)
  private readonly project = inject(ProjectStateService)

  constructor() {
    this.initializeTitleUpdates()
  }

  /**
   * Initialize automatic title updates
   */
  private initializeTitleUpdates(): void {
    // Combine project name and unread message count
    combineLatest([this.project.selectedProject$, this.unreadService.unreadCount$]).subscribe(
      ([projectDetails, unreadCount]) => {
        this.updateTitle(projectDetails?.name ?? '...', unreadCount)
      }
    )
  }

  /**
   * Update the tab title
   */
  private updateTitle(projectTitle: string, unreadCount: number): void {
    let title: string

    if (this.isSystemActive) {
      // Priority to hourglass when system is active
      title = `⏳${unreadCount > 0 ? ` (${unreadCount}) ` : ' '}${projectTitle}`
    } else if (unreadCount > 0) {
      // Red dot only if unread messages and system inactive
      title = `${this.ATTENTION_EMOJI} (${unreadCount}) ${projectTitle}`
    } else {
      // Normal state
      title = projectTitle
    }

    this.document.title = title
    console.log('[TAB-TITLE] Updated to:', title)
  }

  /**
   * Mark system as active (ThinkingEvent received)
   */
  setSystemActive(): void {
    console.log('[TAB-TITLE] System active - showing hourglass')
    this.isSystemActive = true

    // Reset previous timer if exists
    if (this.systemActiveTimer) {
      clearTimeout(this.systemActiveTimer)
    }

    // Start 4-second timer
    this.systemActiveTimer = setTimeout(() => {
      console.log('[TAB-TITLE] System active timeout - hiding hourglass')
      this.isSystemActive = false
      this.forceUpdateTitle()
    }, this.SYSTEM_ACTIVE_TIMEOUT)

    // Update title immediately
    this.forceUpdateTitle()
  }

  /**
   * Mark system as inactive (ChoiceEvent/InviteEvent received)
   */
  setSystemInactive(): void {
    console.log('[TAB-TITLE] System inactive - hiding hourglass')
    this.isSystemActive = false

    // Cancel timer if active
    if (this.systemActiveTimer) {
      clearTimeout(this.systemActiveTimer)
      this.systemActiveTimer = null
    }

    // Update title immediately
    this.forceUpdateTitle()
  }

  /**
   * Force title update with current values
   */
  private forceUpdateTitle(): void {
    const currentProject = this.project.getSelectedProjectId() ?? '...'
    const currentUnread = this.unreadService.getCurrentCount()
    this.updateTitle(currentProject, currentUnread)
  }
}
