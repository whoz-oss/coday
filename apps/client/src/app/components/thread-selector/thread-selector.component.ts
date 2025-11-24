import { Component, EventEmitter, inject, Input, OnInit, Output } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'

import { SessionState } from '@coday/model/session-state'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { toSignal } from '@angular/core/rxjs-interop'
import { ProjectStateService } from '../../core/services/project-state.service'
import { Router } from '@angular/router'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { UserService } from '../../core/services/user.service'

@Component({
  selector: 'app-thread-selector',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './thread-selector.component.html',
  styleUrl: './thread-selector.component.scss',
})
export class ThreadSelectorComponent implements OnInit {
  // State from SessionStateService
  projects: SessionState['projects'] | null = null
  // Search functionality
  @Input() searchMode = false
  @Input() searchQuery = ''
  @Output() searchModeChange = new EventEmitter<boolean>()

  private readonly projectStateService = inject(ProjectStateService)
  private readonly threadStateService = inject(ThreadStateService)
  private readonly threadApiService = inject(ThreadApiService)
  private readonly userService = inject(UserService)
  private readonly router = inject(Router)

  currentThread = toSignal(this.threadStateService.selectedThread$)
  currentProject = toSignal(this.projectStateService.selectedProject$)
  threads = toSignal(this.threadStateService.threadList$)
  isLoadingThreadList = toSignal(this.threadStateService.isLoadingThreadList$)
  username = toSignal(this.userService.username$)

  // Editing state
  editingThreadId: string | null = null
  editingThreadName: string = ''
  isRenamingThread: boolean = false
  renameErrorMessage: string = ''

  ngOnInit(): void {
    // Load current user on component initialization
    this.userService.fetchCurrentUser().subscribe({
      error: (error) => {
        console.error('[THREAD-SELECTOR] Failed to load current user:', error)
      },
    })
  }

  /**
   * Select a thread
   */
  selectThread(threadId: string): void {
    this.threadStateService.selectThread(threadId)

    // navigate to `/project/:projectName/thread/:threadId
    this.router.navigate(['project', this.currentProject()?.name, 'thread', threadId])
  }

  /**
   * Group threads by date categories, with starred threads in a separate section
   */
  getGroupedThreads(): Array<{
    label: string
    threads: Array<{ id: string; name: string; modifiedDate: string; starring: string[] }>
  }> {
    let threadsToGroup = this.threads()
    if (!threadsToGroup?.length) {
      return []
    }

    // Apply search filter if searchQuery is provided
    if (this.searchQuery && this.searchQuery.trim()) {
      const query = this.searchQuery.toLowerCase().trim()
      threadsToGroup = threadsToGroup.filter((thread) => thread.name.toLowerCase().includes(query))
    }

    // If no threads after filtering, return empty
    if (!threadsToGroup.length) {
      return []
    }

    const currentUsername = this.username()
    if (!currentUsername) {
      return []
    }

    // Separate starred and non-starred threads
    const starredThreads = threadsToGroup.filter(
      (thread) => thread.starring && thread.starring.includes(currentUsername)
    )
    const nonStarredThreads = threadsToGroup.filter(
      (thread) => !thread.starring || !thread.starring.includes(currentUsername)
    )

    const groups = new Map<string, Array<{ id: string; name: string; modifiedDate: string; starring: string[] }>>()
    const now = new Date()
    now.setHours(0, 0, 0, 0)

    // Group non-starred threads by date
    for (const thread of nonStarredThreads) {
      const threadDate = new Date(thread.modifiedDate)
      threadDate.setHours(0, 0, 0, 0)
      const diffMs = now.getTime() - threadDate.getTime()
      const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

      let groupLabel: string
      if (diffDays === 0) {
        groupLabel = 'Today'
      } else if (diffDays === 1) {
        groupLabel = 'Yesterday'
      } else if (diffDays < 7) {
        groupLabel = 'This Week'
      } else if (diffDays < 30) {
        groupLabel = 'This Month'
      } else if (diffDays < 90) {
        groupLabel = 'Last 3 Months'
      } else {
        groupLabel = 'Older'
      }

      if (!groups.has(groupLabel)) {
        groups.set(groupLabel, [])
      }
      groups.get(groupLabel)!.push(thread)
    }

    // Build result array with starred section first
    const result: Array<{
      label: string
      threads: Array<{ id: string; name: string; modifiedDate: string; starring: string[] }>
    }> = []

    // Add starred section if there are starred threads
    if (starredThreads.length > 0) {
      // Sort starred threads by modification date (newest first)
      const sortedStarred = [...starredThreads].sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
      result.push({
        label: 'Starred',
        threads: sortedStarred,
      })
    }

    // Add date-grouped sections
    const orderedLabels = ['Today', 'Yesterday', 'This Week', 'This Month', 'Last 3 Months', 'Older']
    orderedLabels
      .filter((label) => groups.has(label))
      .forEach((label) => {
        result.push({
          label,
          threads: groups.get(label)!,
        })
      })

    return result
  }

  /**
   * Check if a thread is starred by the current user
   */
  isStarred(thread: { starring: string[] }): boolean {
    const currentUsername = this.username()
    return !!currentUsername && thread.starring && thread.starring.includes(currentUsername)
  }

  /**
   * Toggle star status for a thread
   */
  toggleStar(event: Event, thread: { id: string; starring: string[] }): void {
    event.stopPropagation()
    const projectName = this.currentProject()?.name
    const currentUsername = this.username()

    if (!projectName || !currentUsername) {
      return
    }

    const isStarred = thread.starring && thread.starring.includes(currentUsername)
    const operation = isStarred
      ? this.threadApiService.unstarThread(projectName, thread.id)
      : this.threadApiService.starThread(projectName, thread.id)

    operation.subscribe({
      next: () => {
        this.threadStateService.refreshThreadList()
      },
      error: (error) => {
        console.error('Error toggling star:', error)
      },
    })
  }

  /**
   * Delete a thread with confirmation
   */
  deleteThread(event: Event, thread: { id: string; name: string }): void {
    event.stopPropagation()
    const projectName = this.currentProject()?.name

    if (!projectName) {
      return
    }

    // Simple confirmation using native dialog
    const confirmed = confirm(`Are you sure you want to delete the thread "${thread.name}"?`)
    if (!confirmed) {
      return
    }

    this.threadApiService.deleteThread(projectName, thread.id).subscribe({
      next: () => {
        // If we're deleting the current thread, clear selection
        if (this.currentThread()?.id === thread.id) {
          this.threadStateService.clearSelection()
          this.router.navigate(['project', projectName])
        }
        this.threadStateService.refreshThreadList()
      },
      error: (error) => {
        console.error('Error deleting thread:', error)
        alert('Failed to delete thread. Please try again.')
      },
    })
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
   * Start editing a thread name
   */
  startEditingThreadName(event: Event, thread: { id: string; name: string }): void {
    event.stopPropagation()
    this.editingThreadId = thread.id
    this.editingThreadName = thread.name || ''
    this.renameErrorMessage = ''

    // Focus the input after Angular renders it
    setTimeout(() => {
      const input = document.querySelector('.thread-name-input') as HTMLInputElement
      if (input) {
        input.focus()
        input.select()
      }
    }, 0)
  }

  /**
   * Cancel editing
   */
  cancelEditingThreadName(): void {
    this.editingThreadId = null
    this.editingThreadName = ''
    this.renameErrorMessage = ''
  }

  /**
   * Save the edited thread name
   */
  saveThreadName(event?: Event): void {
    if (event) {
      event.stopPropagation()
    }

    if (!this.editingThreadId) return

    const newName = this.editingThreadName.trim()

    // Validate name
    if (!newName) {
      this.renameErrorMessage = 'Thread name cannot be empty'
      return
    }

    if (newName.length > 200) {
      this.renameErrorMessage = 'Thread name is too long (max 200 characters)'
      return
    }

    const threadId = this.editingThreadId
    this.isRenamingThread = true
    this.renameErrorMessage = ''

    this.threadStateService.renameThread(threadId, newName).subscribe({
      next: () => {
        console.log('[THREAD-SELECTOR] Thread renamed successfully')
        this.cancelEditingThreadName()
        this.isRenamingThread = false
      },
      error: (error) => {
        console.error('[THREAD-SELECTOR] Error renaming thread:', error)
        this.renameErrorMessage = error.message || 'Failed to rename thread'
        this.isRenamingThread = false
      },
    })
  }

  /**
   * Handle keyboard events during editing
   */
  onEditKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault()
      event.stopPropagation()
      this.saveThreadName()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      this.cancelEditingThreadName()
    }
  }

  /**
   * Handle blur event (save on blur)
   */
  onEditBlur(): void {
    // Small delay to allow click events on buttons to fire first
    setTimeout(() => {
      if (this.editingThreadId && !this.isRenamingThread) {
        this.saveThreadName()
      }
    }, 200)
  }
}
