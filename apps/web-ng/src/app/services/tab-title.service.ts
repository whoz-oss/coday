import { Injectable, inject } from '@angular/core'
import { combineLatest } from 'rxjs'
import { UnreadMessagesService } from './unread-messages.service'
import { CodayService } from '../core/services/coday.service'

@Injectable({
  providedIn: 'root'
})
export class TabTitleService {
  
  // Red dot notification emoji
  private readonly ATTENTION_EMOJI = '🔴'
  
  // System activity state
  private isSystemActive = false
  private systemActiveTimer: any = null
  private readonly SYSTEM_ACTIVE_TIMEOUT = 4000 // 4 seconds
  
  // Modern Angular dependency injection
  private unreadService = inject(UnreadMessagesService)
  private codayService = inject(CodayService)
  
  constructor() {
    this.initializeTitleUpdates()
  }
  
  /**
   * Initialize automatic title updates
   */
  private initializeTitleUpdates(): void {
    // Combine project name and unread message count
    combineLatest([
      this.codayService.projectTitle$,
      this.unreadService.unreadCount$
    ]).subscribe(([projectTitle, unreadCount]) => {
      this.updateTitle(projectTitle, unreadCount)
    })
  }
  
  /**
   * Update the tab title
   */
  private updateTitle(projectTitle: string, unreadCount: number): void {
    let title: string
    
    if (this.isSystemActive) {
      // Priority to hourglass when system is active
      title = `${projectTitle} ⏳${unreadCount > 0 ? ` (${unreadCount})` : ''}`
    } else if (unreadCount > 0) {
      // Red dot only if unread messages and system inactive
      title = `${projectTitle} ${this.ATTENTION_EMOJI} (${unreadCount})`
    } else {
      // Normal state
      title = projectTitle
    }
    
    document.title = title
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
    const currentProject = this.codayService.getCurrentProjectTitle()
    const currentUnread = this.unreadService.getCurrentCount()
    this.updateTitle(currentProject, currentUnread)
  }
}