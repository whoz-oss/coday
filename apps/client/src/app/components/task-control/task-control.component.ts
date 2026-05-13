import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { Router } from '@angular/router'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'

import { TaskCardComponent } from './task-card/task-card.component'
import { SidenavComponent } from '../sidenav/sidenav.component'
import { TaskStatus, TaskStatusService } from '../../core/services/task-status.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { UserService } from '../../core/services/user.service'
import { ProjectEventStreamService } from '../../core/services/project-event-stream.service'
import { ProjectApiService } from '../../core/services/project-api.service'

type FilterKey = 'all' | TaskStatus

interface FilterOption {
  key: FilterKey
  label: string
  icon: string
}

const FILTERS: FilterOption[] = [
  { key: 'all', label: 'All', icon: 'grid_view' },
  { key: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt' },
  { key: 'in-progress', label: 'In progress', icon: 'pending' },
  { key: 'paused', label: 'Paused', icon: 'pause_circle' },
  { key: 'done', label: 'Done', icon: 'check_circle' },
]

@Component({
  selector: 'app-task-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TaskCardComponent, SidenavComponent, MatIconModule, MatButtonModule, MatProgressSpinnerModule],
  templateUrl: './task-control.component.html',
  styleUrl: './task-control.component.scss',
})
export class TaskControlComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly projectState = inject(ProjectStateService)
  private readonly threadState = inject(ThreadStateService)
  private readonly threadApi = inject(ThreadApiService)
  private readonly taskStatus = inject(TaskStatusService)
  private readonly userService = inject(UserService)
  private readonly projectApi = inject(ProjectApiService)
  private readonly destroyRef = inject(DestroyRef)
  // Injected to ensure the service is instantiated and SSE connection is active
  protected readonly _projectEventStream = inject(ProjectEventStreamService)

  protected readonly filters = FILTERS
  protected readonly activeFilter = signal<FilterKey>('all')
  protected readonly starredOnly = signal(false)
  protected readonly isCreating = signal(false)

  protected readonly isLoadingList = toSignal(this.threadState.isLoadingThreadList$, { initialValue: false })
  protected readonly username = toSignal(this.userService.username$, { initialValue: null })

  protected readonly allTasks = this.taskStatus.tasks

  protected readonly filteredTasks = computed(() => {
    const filter = this.activeFilter()
    const currentUsername = this.username()
    let tasks = this.allTasks()

    if (this.starredOnly() && currentUsername) {
      tasks = tasks.filter((t) => t.starring.includes(currentUsername))
    }

    if (filter === 'all') return tasks
    return tasks.filter((t) => t.status === filter)
  })

  /** Count per filter for badges */
  protected readonly filterCounts = computed(() => {
    const currentUsername = this.username()
    let tasks = this.allTasks()

    if (this.starredOnly() && currentUsername) {
      tasks = tasks.filter((t) => t.starring.includes(currentUsername))
    }

    return {
      all: tasks.length,
      'waiting-you': tasks.filter((t) => t.status === 'waiting-you').length,
      'in-progress': tasks.filter((t) => t.status === 'in-progress').length,
      paused: tasks.filter((t) => t.status === 'paused').length,
      done: tasks.filter((t) => t.status === 'done').length,
    } as Record<FilterKey, number>
  })

  /** Groups for "All" view: sections by status (only non-empty) */
  protected readonly taskGroups = computed(() => {
    const currentUsername = this.username()
    let tasks = this.allTasks()

    if (this.starredOnly() && currentUsername) {
      tasks = tasks.filter((t) => t.starring.includes(currentUsername))
    }
    const groups: { status: TaskStatus; label: string; icon: string; tasks: typeof tasks }[] = [
      { status: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt', tasks: [] },
      { status: 'in-progress', label: 'In progress', icon: 'pending', tasks: [] },
      { status: 'done', label: 'Done', icon: 'check_circle', tasks: [] },
      { status: 'paused', label: 'Paused', icon: 'pause_circle', tasks: [] },
      { status: 'error', label: 'Error', icon: 'error', tasks: [] },
    ]
    for (const task of tasks) {
      const group = groups.find((g) => g.status === task.status)
      if (group) group.tasks.push(task)
    }
    return groups.filter((g) => g.tasks.length > 0)
  })

  ngOnInit(): void {
    // Ensure username is loaded for star operations
    this.userService
      .fetchCurrentUser()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ error: (err) => console.warn('[TASK-CONTROL] Could not fetch user:', err) })
  }

  setFilter(key: FilterKey): void {
    this.activeFilter.set(key)
  }

  toggleStarredFilter(): void {
    this.starredOnly.update((v) => !v)
  }

  createTask(): void {
    const project = this.projectState.getSelectedProjectId()
    if (!project || this.isCreating()) return

    this.isCreating.set(true)
    this.threadApi
      .createThread()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isCreating.set(false)
          void this.router.navigate(['project', project, 'thread', response.thread.id])
        },
        error: (err) => {
          console.error('[TASK-CONTROL] Failed to create thread:', err)
          this.isCreating.set(false)
        },
      })
  }

  onStopRequested(threadId: string): void {
    this.threadApi
      .stopThread(threadId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err) => console.error('[TASK-CONTROL] Failed to stop thread:', err),
      })
  }

  onDeleteRequested(threadId: string): void {
    this.threadApi
      .deleteThread(threadId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.threadState.refreshThreadList(),
        error: (err) => console.error('[TASK-CONTROL] Failed to delete thread:', err),
      })
  }

  onStarToggled(threadId: string): void {
    const currentUsername = this.username()
    if (!currentUsername) return

    const task = this.allTasks().find((t) => t.id === threadId)
    if (!task) return

    const isStarred = task.starring.includes(currentUsername)
    const op$ = isStarred ? this.threadApi.unstarThread(threadId) : this.threadApi.starThread(threadId)

    // Optimistic update
    this.threadState.updateStarLocal(threadId, !isStarred, currentUsername)

    op$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      error: (err) => {
        console.error('[TASK-CONTROL] Failed to toggle star:', err)
        this.threadState.refreshThreadList()
      },
    })
  }

  onMarkDoneRequested(threadId: string): void {
    const project = this.projectState.getSelectedProjectId()
    if (!project) return
    this.projectApi
      .markThreadDone(project, threadId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.threadState.refreshThreadList(),
        error: (err) => console.error('[TASK-CONTROL] Failed to mark task as done:', err),
      })
  }

  onMarkActiveRequested(threadId: string): void {
    const project = this.projectState.getSelectedProjectId()
    if (!project) return
    this.projectApi
      .markThreadActive(project, threadId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.threadState.refreshThreadList(),
        error: (err) => console.error('[TASK-CONTROL] Failed to mark task as active:', err),
      })
  }
}
