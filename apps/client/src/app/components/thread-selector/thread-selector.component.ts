import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { SessionStateService } from '../../core/services/session-state.service'
import { CodayService } from '../../core/services/coday.service'
import { SessionState } from '@coday/model/session-state'

@Component({
  selector: 'app-thread-selector',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './thread-selector.component.html',
  styleUrl: './thread-selector.component.scss'
})
export class ThreadSelectorComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  
  // State from SessionStateService
  threads: SessionState['threads'] | null = null
  projects: SessionState['projects'] | null = null
  isExpanded = false
  
  // Modern Angular dependency injection
  private sessionState = inject(SessionStateService)
  private codayService = inject(CodayService)
  
  ngOnInit(): void {
    // Subscribe to threads state
    this.sessionState.getThreads$()
      .pipe(takeUntil(this.destroy$))
      .subscribe(threads => {
        console.log('[THREAD-SELECTOR] Threads updated:', threads)
        this.threads = threads
      })
    
    // Subscribe to projects state (to know if we have a project selected)
    this.sessionState.getProjects$()
      .pipe(takeUntil(this.destroy$))
      .subscribe(projects => {
        console.log('[THREAD-SELECTOR] Projects updated:', projects)
        this.projects = projects
      })
  }
  
  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
  
  /**
   * Toggle the thread dropdown
   */
  toggleDropdown(): void {
    if (this.canShowDropdown()) {
      this.isExpanded = !this.isExpanded
    }
  }
  
  /**
   * Close the dropdown
   */
  closeDropdown(): void {
    this.isExpanded = false
  }
  
  /**
   * Select a thread
   */
  selectThread(threadId: string): void {
    console.log('[THREAD-SELECTOR] Selecting thread:', threadId)
    
    // Use the thread select command with ID
    this.codayService.sendMessage(`thread select ${threadId}`)
    
    // Close dropdown
    this.closeDropdown()
  }
  
  /**
   * Create a new thread
   */
  createNewThread(): void {
    console.log('[THREAD-SELECTOR] Creating new thread')
    
    // Use thread new command to create a new thread
    this.codayService.sendMessage('thread new')
    
    // Close dropdown
    this.closeDropdown()
  }
  
  /**
   * Check if we can show the dropdown
   */
  canShowDropdown(): boolean {
    // Need a project selected and either threads available or ability to create
    return this.hasProjectSelected() && this.isThreadManagementAvailable()
  }
  
  /**
   * Check if thread management is available
   */
  isThreadManagementAvailable(): boolean {
    // Always true if project selected (can always create threads)
    return this.hasProjectSelected()
  }
  
  /**
   * Check if a project is selected
   */
  hasProjectSelected(): boolean {
    return !!(this.projects?.current)
  }
  
  /**
   * Get display text for the current state
   */
  getDisplayText(): string {
    if (!this.projects || !this.threads) {
      return 'Loading...'
    }
    
    if (!this.hasProjectSelected()) {
      return 'No project selected'
    }
    
    // Get current thread name if available
    if (this.threads.current && this.threads.list) {
      const currentThread = this.threads.list.find(t => t.id === this.threads!.current)
      if (currentThread) {
        return this.truncateText(currentThread.name, 25) // Truncate for display
      }
    }
    
    // Check if we have threads available
    if (this.threads.list && this.threads.list.length > 0) {
      return 'Select a thread'
    }
    
    return 'New thread'
  }
  
  /**
   * Get tooltip text (full text for long names)
   */
  getTooltipText(): string {
    if (!this.projects || !this.threads) {
      return ''
    }
    
    if (!this.hasProjectSelected()) {
      return 'No project selected'
    }
    
    // Show full thread name in tooltip if available
    if (this.threads.current && this.threads.list) {
      const currentThread = this.threads.list.find(t => t.id === this.threads!.current)
      if (currentThread) {
        return currentThread.name
      }
    }
    
    return ''
  }
  
  /**
   * Get the icon to display
   */
  getIcon(): string {
    if (!this.projects || !this.threads) {
      return '⏳'
    }
    
    if (!this.hasProjectSelected()) {
      return '' // No icon when no project
    }
    
    if (this.threads.current) {
      return '🧵' // Thread icon when thread is selected
    }
    
    return '' // No icon when no thread selected but project available
  }
  
  /**
   * Check if the selector is in a clickable state
   */
  isClickable(): boolean {
    return this.canShowDropdown()
  }
  
  /**
   * Get available threads
   */
  getAvailableThreads(): Array<{ id: string; name: string; modifiedDate: string }> {
    if (!this.threads?.list) {
      return []
    }
    
    // Sort by modified date (most recent first)
    return [...this.threads.list].sort((a, b) => {
      return new Date(b.modifiedDate).getTime() - new Date(a.modifiedDate).getTime()
    })
  }
  
  /**
   * Check if we have threads available
   */
  hasThreadsAvailable(): boolean {
    return !!(this.threads?.list && this.threads.list.length > 0)
  }
  
  /**
   * Get current thread name for display
   */
  getCurrentThreadName(): string | null {
    if (!this.threads?.current || !this.threads?.list) {
      return null
    }
    
    const currentThread = this.threads.list.find(t => t.id === this.threads!.current)
    return currentThread ? currentThread.name : null
  }
  
  /**
   * Truncate text for display
   */
  truncateText(text: string, maxLength: number): string {
    if (text.length <= maxLength) {
      return text
    }
    return text.substring(0, maxLength) + '...'
  }
  
  /**
   * Format date for display
   */
  formatDate(dateString: string): string {
    try {
      const date = new Date(dateString)
      const now = new Date()
      const diffMs = now.getTime() - date.getTime()
      const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))
      
      if (diffDays === 0) {
        return 'Today'
      } else if (diffDays === 1) {
        return 'Yesterday'
      } else if (diffDays < 7) {
        return `${diffDays} days ago`
      } else {
        return date.toLocaleDateString('en-EN', { 
          day: 'numeric', 
          month: 'short' 
        })
      }
    } catch (error) {
      return 'No date'
    }
  }
  
  /**
   * Track by function for thread list
   */
  trackByThreadId(_index: number, thread: { id: string; name: string; modifiedDate: string }): string {
    return thread.id
  }
  
  /**
   * Handle click outside to close dropdown
   */
  onDocumentClick(event: Event): void {
    const target = event.target as HTMLElement
    if (!target.closest('.thread-selector')) {
      this.closeDropdown()
    }
  }
}