import { Component, EventEmitter, inject, Input, Output } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { SessionState } from '@coday/model/session-state'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { toSignal } from '@angular/core/rxjs-interop'
import { ProjectStateService } from '../../core/services/project-state.service'
import { Router } from '@angular/router'

@Component({
  selector: 'app-thread-selector',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatButtonModule, MatInputModule, MatFormFieldModule],
  templateUrl: './thread-selector.component.html',
  styleUrl: './thread-selector.component.scss',
})
export class ThreadSelectorComponent {
  // State from SessionStateService
  projects: SessionState['projects'] | null = null
  // Search functionality
  @Input() searchMode = false
  @Input() searchQuery = ''
  @Output() searchModeChange = new EventEmitter<boolean>()

  private readonly projectStateService = inject(ProjectStateService)
  private readonly threadStateService = inject(ThreadStateService)
  private readonly router = inject(Router)

  currentThread = toSignal(this.threadStateService.selectedThread$)
  currentProject = toSignal(this.projectStateService.selectedProject$)
  threads = toSignal(this.threadStateService.threadList$)

  /**
   * Select a thread
   */
  selectThread(threadId: string): void {
    this.threadStateService.selectThread(threadId)

    // navigate to `/project/:projectName/thread/:threadId
    this.router.navigate(['/project', this.currentProject()?.name, 'thread', threadId])
  }

  /**
   * Group threads by date categories
   */
  getGroupedThreads(): Array<{ label: string; threads: Array<{ id: string; name: string; modifiedDate: string }> }> {
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

    const groups = new Map<string, Array<{ id: string; name: string; modifiedDate: string }>>()
    const now = new Date()
    now.setHours(0, 0, 0, 0)

    for (const thread of threadsToGroup) {
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

    // Convert to array and maintain order
    const orderedLabels = ['Today', 'Yesterday', 'This Week', 'This Month', 'Last 3 Months', 'Older']
    return orderedLabels
      .filter((label) => groups.has(label))
      .map((label) => ({
        label,
        threads: groups.get(label)!,
      }))
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
