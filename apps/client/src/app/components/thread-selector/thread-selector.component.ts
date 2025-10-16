import { Component, inject, OnDestroy, OnInit } from '@angular/core'
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
  styleUrl: './thread-selector.component.scss',
})
export class ThreadSelectorComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()

  // State from SessionStateService
  threads: SessionState['threads'] | null = null
  projects: SessionState['projects'] | null = null

  // Modern Angular dependency injection
  private sessionState = inject(SessionStateService)
  private codayService = inject(CodayService)

  ngOnInit(): void {
    // Subscribe to threads state
    this.sessionState
      .getThreads$()
      .pipe(takeUntil(this.destroy$))
      .subscribe((threads) => {
        console.log('[THREAD-SELECTOR] Threads updated:', threads)
        this.threads = threads
      })

    // Subscribe to projects state (to know if we have a project selected)
    this.sessionState
      .getProjects$()
      .pipe(takeUntil(this.destroy$))
      .subscribe((projects) => {
        console.log('[THREAD-SELECTOR] Projects updated:', projects)
        this.projects = projects
      })
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  /**
   * Select a thread
   */
  selectThread(threadId: string): void {
    console.log('[THREAD-SELECTOR] Selecting thread:', threadId)

    // Use the thread select command with ID
    this.codayService.sendMessage(`thread select ${threadId}`)
  }

  /**
   * Create a new thread
   */
  createNewThread(): void {
    console.log('[THREAD-SELECTOR] Creating new thread')

    // Use thread new command to create a new thread
    this.codayService.sendMessage('thread new')
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
    return !!this.projects?.current
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

    const currentThread = this.threads.list.find((t) => t.id === this.threads!.current)
    return currentThread ? currentThread.name : null
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
          month: 'short',
        })
      }
    } catch (error) {
      return 'No date'
    }
  }
}
