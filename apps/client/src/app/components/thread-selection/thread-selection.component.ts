import { Component, OnInit, OnDestroy, inject, ViewChild, ElementRef } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Router, ActivatedRoute } from '@angular/router'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { ThreadApiService, ThreadSummary } from '../../core/services/thread-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'

/**
 * Component for thread selection and creation within a project.
 *
 * Displays:
 * - List of existing threads for the project
 * - Textarea for creating a new thread by typing the first message
 *
 * After a thread is selected or created, navigates to:
 * /project/:projectName/thread/:threadId
 */
@Component({
  selector: 'app-thread-selection',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './thread-selection.component.html',
  styleUrl: './thread-selection.component.scss',
})
export class ThreadSelectionComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()

  @ViewChild('messageInput') messageInput?: ElementRef<HTMLTextAreaElement>

  // State
  projectName: string = ''
  threads: ThreadSummary[] = []
  isLoadingThreads: boolean = true
  isCreatingThread: boolean = false
  error: string | null = null

  // First message for implicit thread creation
  firstMessage: string = ''

  // Inject services
  private threadApi = inject(ThreadApiService)
  private projectState = inject(ProjectStateService)
  protected router = inject(Router) // protected for template access
  private route = inject(ActivatedRoute)

  ngOnInit(): void {
    // Get project name from route
    this.projectName = this.route.snapshot.params['projectName']

    if (!this.projectName) {
      console.error('[THREAD-SELECTION] No project name in route')
      this.router.navigate(['/'])
      return
    }

    console.log('[THREAD-SELECTION] Initialized for project:', this.projectName)

    // Ensure project state is set
    if (this.projectState.getSelectedProjectName() !== this.projectName) {
      this.projectState.selectProject(this.projectName).pipe(takeUntil(this.destroy$)).subscribe()
    }

    // Load threads for this project
    this.loadThreads()
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  /**
   * Load threads for the current project
   */
  private loadThreads(): void {
    this.isLoadingThreads = true
    this.error = null

    this.threadApi
      .listThreads(this.projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[THREAD-SELECTION] Threads loaded:', response.threads.length)
          this.threads = this.sortThreadsByDate(response.threads)
          this.isLoadingThreads = false
        },
        error: (error) => {
          console.error('[THREAD-SELECTION] Error loading threads:', error)
          this.error = 'Failed to load threads. Please try again.'
          this.isLoadingThreads = false
        },
      })
  }

  /**
   * Sort threads by modified date (most recent first)
   */
  private sortThreadsByDate(threads: ThreadSummary[]): ThreadSummary[] {
    return [...threads].sort((a, b) => {
      return new Date(b.modifiedDate).getTime() - new Date(a.modifiedDate).getTime()
    })
  }

  /**
   * Select an existing thread
   */
  selectThread(threadId: string): void {
    console.log('[THREAD-SELECTION] Selecting thread:', threadId)

    // Navigate to chat interface
    this.router.navigate(['/project', this.projectName, 'thread', threadId])
  }

  /**
   * Create a new thread with the first message
   */
  async createThreadWithMessage(): Promise<void> {
    if (!this.firstMessage.trim()) {
      return
    }

    console.log('[THREAD-SELECTION] Creating thread with first message')
    this.isCreatingThread = true
    this.error = null

    this.threadApi
      .createThread(this.projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[THREAD-SELECTION] Thread created:', response.thread.id)

          const threadId = response.thread.id
          const message = this.firstMessage.trim()

          // Navigate to chat interface with first message in router state
          this.router.navigate(['/project', this.projectName, 'thread', threadId], {
            state: { firstMessage: message },
          })
        },
        error: (error) => {
          console.error('[THREAD-SELECTION] Error creating thread:', error)
          this.error = `Failed to create thread: ${error.message || 'Unknown error'}`
          this.isCreatingThread = false
        },
      })
  }

  /**
   * Handle Enter key in textarea (with Cmd/Ctrl modifier to send)
   */
  onKeyDown(event: KeyboardEvent): void {
    // Cmd+Enter or Ctrl+Enter to create thread
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault()
      this.createThreadWithMessage()
    }
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

  /**
   * Track by function for thread list
   */
  trackByThreadId(_index: number, thread: ThreadSummary): string {
    return thread.id
  }

  /**
   * Retry loading threads after error
   */
  retry(): void {
    this.loadThreads()
  }
}
