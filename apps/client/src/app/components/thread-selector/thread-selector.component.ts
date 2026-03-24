import { Component, effect, EventEmitter, inject, Input, OnInit, Output } from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { MatTooltipModule } from '@angular/material/tooltip'

import { SessionState } from '@coday/model'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { toSignal } from '@angular/core/rxjs-interop'
import { ProjectStateService } from '../../core/services/project-state.service'
import { Router } from '@angular/router'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { UserService } from '../../core/services/user.service'

/** Recursive node type for sub-thread tree rendering */
export interface SubThreadNode {
  id: string
  name: string
  modifiedDate: string
  starring: string[]
  summary: string
  delegatedAgentName?: string
  delegatedTask?: string
  subThreads?: SubThreadNode[]
}

/** Root-level thread node enriched with a recursive sub-thread tree */
export interface ThreadNode extends SubThreadNode {
  parentThreadId?: string
  username?: string
  users?: { userId: string }[]
}

@Component({
  selector: 'app-thread-selector',
  standalone: true,
  imports: [
    NgTemplateOutlet,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
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

  // Sub-thread collapse/expand state (keyed by parent thread ID)
  expandedParents = new Set<string>()

  constructor() {
    // Auto-expand ancestors when the selected thread changes or the thread list loads
    effect(() => {
      const currentThreadId = this.currentThread()?.id
      const allThreads = this.threads()
      if (!currentThreadId || !allThreads?.length) return

      // Build a parentId lookup map
      const parentMap = new Map<string, string>()
      for (const t of allThreads as any[]) {
        if (t.parentThreadId) parentMap.set(t.id, t.parentThreadId)
      }

      // Walk up the ancestor chain and expand each one
      let id: string | undefined = currentThreadId
      while (id) {
        const parentId = parentMap.get(id)
        if (parentId) this.expandedParents.add(parentId)
        id = parentId
      }
    })
  }

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
   * Group threads by date categories, with starred threads in a separate section.
   * Sub-threads (those with parentThreadId) are excluded from the main list
   * and instead attached to their parent thread for recursive inline display.
   * Supports multi-level nesting: a sub-thread can itself have sub-threads.
   */
  getGroupedThreads(): Array<{ label: string; threads: ThreadNode[] }> {
    let allThreads = this.threads()
    if (!allThreads?.length) {
      return []
    }

    // Separate root threads from sub-threads
    const allIds = new Set(allThreads.map((t: any) => t.id))
    const rootThreads = allThreads.filter((t: any) => !t.parentThreadId)
    const subThreads = allThreads.filter((t: any) => !!t.parentThreadId)

    // Sub-threads whose parent is not accessible are promoted to root threads
    const orphanSubThreads = subThreads.filter((t: any) => !allIds.has(t.parentThreadId))
    const attachedSubThreads = subThreads.filter((t: any) => allIds.has(t.parentThreadId))

    // Build a map of parentThreadId -> children (any depth)
    const subThreadMap = new Map<string, any[]>()
    for (const sub of attachedSubThreads) {
      const parentId = sub.parentThreadId as string
      if (!subThreadMap.has(parentId)) {
        subThreadMap.set(parentId, [])
      }
      subThreadMap.get(parentId)!.push(sub)
    }

    // Recursively build the sub-thread tree for a given thread id
    const buildSubTree = (threadId: string): SubThreadNode[] => {
      const children = subThreadMap.get(threadId) || []
      return children
        .sort((a: any, b: any) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
        .map((child: any) => ({
          ...child,
          subThreads: buildSubTree(child.id),
        }))
    }

    // Apply search filter to root threads + promoted orphan sub-threads
    let threadsToGroup = [...rootThreads, ...orphanSubThreads]
    if (this.searchQuery && this.searchQuery.trim()) {
      const query = this.searchQuery.toLowerCase().trim()
      threadsToGroup = threadsToGroup.filter(
        (thread: any) =>
          thread.name.toLowerCase().includes(query) || (thread.summary && thread.summary.toLowerCase().includes(query))
      )
    }

    if (!threadsToGroup.length) {
      return []
    }

    const currentUsername = this.username()
    if (!currentUsername) {
      return []
    }

    // Attach recursive sub-thread trees to root threads
    const enrichedThreads: ThreadNode[] = threadsToGroup.map((thread: any) => ({
      ...thread,
      subThreads: buildSubTree(thread.id),
    }))

    // Separate starred and non-starred threads
    const starredThreads = enrichedThreads.filter(
      (thread) => thread.starring && thread.starring.includes(currentUsername)
    )
    const nonStarredThreads = enrichedThreads.filter(
      (thread) => !thread.starring || !thread.starring.includes(currentUsername)
    )

    const groups = new Map<string, ThreadNode[]>()
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
    const result: Array<{ label: string; threads: ThreadNode[] }> = []

    // Add starred section if there are starred threads
    if (starredThreads.length > 0) {
      const sortedStarred = [...starredThreads].sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
      result.push({ label: 'Starred', threads: sortedStarred })
    }

    // Add date-grouped sections
    const orderedLabels = ['Today', 'Yesterday', 'This Week', 'This Month', 'Last 3 Months', 'Older']
    orderedLabels
      .filter((label) => groups.has(label))
      .forEach((label) => {
        result.push({ label, threads: groups.get(label)! })
      })

    return result
  }

  /**
   * Check if a parent thread's sub-threads are expanded
   */
  isParentExpanded(threadId: string): boolean {
    return this.expandedParents.has(threadId)
  }

  /**
   * Toggle sub-thread list visibility for a parent thread
   */
  toggleSubThreads(event: Event, threadId: string): void {
    event.stopPropagation()
    if (this.expandedParents.has(threadId)) {
      this.expandedParents.delete(threadId)
    } else {
      this.expandedParents.add(threadId)
    }
  }

  /**
   * Check if a thread is a sub-thread (has parentThreadId)
   */
  isSubThread(thread: any): boolean {
    return !!thread.parentThreadId
  }

  /**
   * Check if a thread is shared (has more than 1 user)
   */
  isSharedThread(thread: any): boolean {
    return thread.users && thread.users.length > 1
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
    const currentUsername = this.username()

    if (!currentUsername) {
      return
    }

    const isStarred = thread.starring && thread.starring.includes(currentUsername)
    const operation = isStarred
      ? this.threadApiService.unstarThread(thread.id)
      : this.threadApiService.starThread(thread.id)

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

    this.threadApiService.deleteThread(thread.id).subscribe({
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
