import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core'
import { Router, RouterLink } from '@angular/router'
import { BreakpointObserver } from '@angular/cdk/layout'
import { map } from 'rxjs/operators'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatTooltipModule } from '@angular/material/tooltip'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { MatMenuModule } from '@angular/material/menu'
import { MatDividerModule } from '@angular/material/divider'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatInputModule } from '@angular/material/input'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { buildCodayEvent, ChoiceEvent, InviteEvent, ThinkingEvent, ThreadUpdateEvent } from '@coday/model'
import { TaskCardComponent } from '../task-control/task-card/task-card.component'
import { NewTaskDialogComponent } from '../new-task-dialog/new-task-dialog.component'
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component'
import { OptionsPanelComponent } from '../options-panel/options-panel.component'
import { ThreadComponent } from '../thread/thread.component'
import { GlobalTaskService } from '../../core/services/global-task.service'
import { ConfigStateService } from '../../core/services/config-state.service'
import { UserService } from '../../core/services/user.service'
import { TaskStatus } from '../../core/services/task-status.service'
import { PreferencesService } from '../../services/preferences.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ThreadStateService } from '../../core/services/thread-state.service'

const MIN_PANEL_WIDTH_PERCENT = 20
const MAX_PANEL_WIDTH_PERCENT = 70

type FilterKey = 'all' | TaskStatus

const STATUS_GROUPS: { status: TaskStatus; label: string; icon: string }[] = [
  { status: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt' },
  { status: 'in-progress', label: 'In progress', icon: 'pending' },
  { status: 'paused', label: 'Paused', icon: 'pause_circle' },
  { status: 'error', label: 'Error', icon: 'error' },
  { status: 'done', label: 'Done', icon: 'check_circle' },
]

const FILTERS: { key: FilterKey; label: string; icon: string }[] = [
  { key: 'all', label: 'All', icon: 'grid_view' },
  { key: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt' },
  { key: 'in-progress', label: 'In progress', icon: 'pending' },
  { key: 'paused', label: 'Paused', icon: 'pause_circle' },
  { key: 'done', label: 'Done', icon: 'check_circle' },
]

/**
 * Global Task Control dashboard — the app home page.
 *
 * Aggregates threads from ALL accessible projects using GlobalTaskService,
 * which calls each project's thread list endpoint in parallel.
 *
 * For live status updates (waiting-you / in-progress), opens a SINGLE global
 * SSE connection to /api/event-stream which aggregates events from all projects.
 * This prevents saturating browser HTTP/1.1 connection limits (max ~6 per domain)
 * when many projects are present.
 */
@Component({
  selector: 'app-global-task-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TaskCardComponent,
    ThreadComponent,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatMenuModule,
    MatDividerModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
  ],
  templateUrl: './global-task-control.component.html',
  styleUrl: './global-task-control.component.scss',
})
export class GlobalTaskControlComponent implements OnInit {
  private readonly globalTaskService = inject(GlobalTaskService)
  private readonly destroyRef = inject(DestroyRef)
  private readonly dialog = inject(MatDialog)
  private readonly router = inject(Router)
  private readonly configState = inject(ConfigStateService)
  private readonly userService = inject(UserService)
  private readonly preferences = inject(PreferencesService)
  private readonly projectState = inject(ProjectStateService)
  private readonly threadState = inject(ThreadStateService)
  private readonly breakpointObserver = inject(BreakpointObserver)

  private readonly username = toSignal(this.userService.username$, { initialValue: null })

  // ── Preview panel ──────────────────────────────────────────────────────────
  /** True on viewports >= 1280px. Preview panel is desktop-only. */
  protected readonly isDesktop = toSignal(
    this.breakpointObserver.observe('(min-width: 1280px)').pipe(map((s) => s.matches)),
    { initialValue: false }
  )
  protected readonly previewEnabled = toSignal(this.preferences.missionPreviewEnabled$, { initialValue: false })
  protected readonly previewActive = computed(() => this.previewEnabled() && this.isDesktop())
  protected readonly previewThreadId = signal<string | null>(null)
  protected readonly previewProjectId = signal<string | null>(null)
  protected readonly previewTaskName = signal<string | null>(null)
  protected readonly previewPanelWidthPercent = signal<number>(this.preferences.getMissionPreviewPanelWidth())

  // Resize drag state
  private isDraggingResizer = false
  private dragStartX = 0
  private dragStartWidth = 0
  private containerWidth = 0

  @ViewChild('layoutContainer') private layoutContainerRef?: ElementRef<HTMLElement>

  /** Single global SSE connection aggregating events from all projects */
  private globalEventSource: EventSource | null = null

  protected readonly filters = FILTERS
  protected readonly activeFilter = signal<FilterKey>('all')
  protected readonly starredOnly = signal(false)
  protected readonly activeProject = signal<string | null>(null)
  protected readonly searchQuery = signal<string>('')

  protected readonly isLoading = this.globalTaskService.isLoading
  protected readonly allTasks = this.globalTaskService.tasks

  /** Unique project names with their task counts */
  protected readonly projectSummaries = computed(() => {
    const tasks = this.allTasks()
    const countByProject = new Map<string, number>()
    for (const t of tasks) {
      countByProject.set(t.projectId, (countByProject.get(t.projectId) ?? 0) + 1)
    }
    return [...countByProject.entries()]
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => a.name.localeCompare(b.name))
  })

  /** Tasks filtered by active project, starred filter, and active status filter */
  protected readonly filteredTasks = computed(() => {
    let tasks = this.allTasks()

    const project = this.activeProject()
    if (project) {
      tasks = tasks.filter((t) => t.projectId === project)
    }

    const currentUsername = this.username()
    if (this.starredOnly() && currentUsername) {
      tasks = tasks.filter((t) => t.starring.includes(currentUsername))
    }

    const filter = this.activeFilter()
    if (filter !== 'all') {
      tasks = tasks.filter((t) => t.status === filter)
    }

    return tasks
  })

  /** Counts for filter badges (after project + starred filter, before status filter) */
  protected readonly filterCounts = computed((): Record<FilterKey, number> => {
    let tasks = this.allTasks()
    const project = this.activeProject()
    if (project) tasks = tasks.filter((t) => t.projectId === project)
    const currentUsername = this.username()
    if (this.starredOnly() && currentUsername) tasks = tasks.filter((t) => t.starring.includes(currentUsername))

    return {
      all: tasks.length,
      'waiting-you': tasks.filter((t) => t.status === 'waiting-you').length,
      'in-progress': tasks.filter((t) => t.status === 'in-progress').length,
      done: tasks.filter((t) => t.status === 'done').length,
      paused: tasks.filter((t) => t.status === 'paused').length,
      error: tasks.filter((t) => t.status === 'error').length,
    }
  })

  /** Tasks after search filter applied */
  protected readonly displayedTasks = computed(() => {
    const q = this.searchQuery().toLowerCase().trim()
    const tasks = this.sortedFilteredTasks()
    if (!q) return tasks
    return tasks.filter((t) => (t.name || '').toLowerCase().includes(q) || (t.summary || '').toLowerCase().includes(q))
  })

  /** Groups for the "All" status view */
  protected readonly taskGroups = computed(() => {
    const tasks = this.displayedTasks()
    return STATUS_GROUPS.map((g) => ({
      ...g,
      tasks: tasks.filter((t) => t.status === g.status),
    })).filter((g) => g.tasks.length > 0)
  })

  /** Sorted tasks: waiting-you → in-progress → paused → done → error */
  protected readonly sortedFilteredTasks = computed(() => {
    const tasks = this.filteredTasks()
    const priority: Record<TaskStatus, number> = {
      'waiting-you': 0,
      'in-progress': 1,
      paused: 2,
      error: 3,
      done: 4,
    }
    return [...tasks].sort((a, b) => (priority[a.status] ?? 99) - (priority[b.status] ?? 99))
  })

  constructor() {
    // Close the SSE connection when the component is destroyed
    this.destroyRef.onDestroy(() => this.disconnectGlobalSse())
  }

  ngOnInit(): void {
    this.globalTaskService.refresh()
    this.connectGlobalSse()
    this.userService
      .fetchCurrentUser()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err) => console.warn('[GTC] Could not fetch user:', err),
      })
  }

  protected setFilter(key: FilterKey): void {
    this.activeFilter.set(key)
  }

  protected toggleStarredFilter(): void {
    this.starredOnly.update((v) => !v)
  }

  protected setProject(name: string | null): void {
    this.activeProject.set(name)
    this.activeFilter.set('all')
  }

  protected onSearchInput(event: Event): void {
    this.searchQuery.set((event.target as HTMLInputElement).value)
  }

  protected clearSearch(): void {
    this.searchQuery.set('')
  }

  protected refresh(): void {
    this.globalTaskService.refresh()
  }

  protected openPreferences(): void {
    this.dialog.open(OptionsPanelComponent, {
      width: '400px',
    })
  }

  protected openTokenUsage(): void {
    void this.router.navigate(['/token-usage'])
  }

  protected openUserConfig(): void {
    this.configState.openUserConfigEditor()
  }

  protected openNewTaskDialog(): void {
    const ref = this.dialog.open(NewTaskDialogComponent, {
      width: '500px',
      disableClose: false,
    })
    ref.afterClosed().subscribe((result: { threadId?: string; projectId: string; navigate?: boolean } | null) => {
      if (!result) return

      if (result.navigate) {
        // Navigate to the project and open a new thread
        void this.router.navigate(['project', result.projectId])
      } else {
        // Full task created — refresh the task list
        setTimeout(() => this.globalTaskService.refresh(), 300)
        setTimeout(() => this.globalTaskService.refresh(), 1500)
      }
    })
  }

  protected onStopRequested(threadId: string, projectId: string): void {
    this.globalTaskService.stopThread(projectId, threadId)
  }

  protected onDeleteRequested(threadId: string, projectId: string): void {
    this.globalTaskService.deleteThread(projectId, threadId)
  }

  protected onStarToggled(threadId: string, projectId: string): void {
    const task = this.allTasks().find((t) => t.id === threadId)
    if (!task) return
    const currentUsername = this.username()
    const isStarred = !!currentUsername && task.starring.includes(currentUsername)
    this.globalTaskService.toggleStar(projectId, threadId, isStarred)
  }

  protected onCloseTaskRequested(threadId: string, projectId: string): void {
    const task = this.allTasks().find((t) => t.id === threadId)
    const worktreeName = task?.worktreeProject ?? projectId
    const ref = this.dialog.open(ConfirmDialogComponent, {
      width: '420px',
      data: {
        title: 'Close task',
        message: `This will stop the agent, delete the thread and remove the worktree project "${worktreeName}" from disk. This cannot be undone.`,
        confirmLabel: 'Close task',
        cancelLabel: 'Cancel',
      },
    })
    ref.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {
        this.globalTaskService.closeTask(projectId, threadId)
      }
    })
  }

  protected onMarkDoneRequested(threadId: string, projectId: string): void {
    this.globalTaskService.markTaskDone(projectId, threadId)
  }

  protected onMarkActiveRequested(threadId: string, projectId: string): void {
    this.globalTaskService.markTaskActive(projectId, threadId)
  }

  // ── Preview panel handlers ──────────────────────────────────────────────────

  protected onPreviewRequested(threadId: string, projectId: string, taskName: string): void {
    if (this.previewThreadId() === threadId) {
      this.previewThreadId.set(null)
      this.previewProjectId.set(null)
      this.previewTaskName.set(null)
    } else {
      this.projectState.selectProject(projectId)
      this.threadState.selectThread(threadId)
      this.previewProjectId.set(projectId)
      this.previewThreadId.set(threadId)
      this.previewTaskName.set(taskName)
    }
  }

  protected closePreview(): void {
    this.previewThreadId.set(null)
    this.previewProjectId.set(null)
    this.previewTaskName.set(null)
  }

  protected navigateToThread(): void {
    const threadId = this.previewThreadId()
    const projectId = this.previewProjectId()
    if (!threadId || !projectId) return
    void this.router.navigate(['project', projectId, 'thread', threadId])
  }

  // ── Resizer drag ─────────────────────────────────────────────────────────

  protected onResizerMousedown(event: MouseEvent): void {
    event.preventDefault()
    this.isDraggingResizer = true
    this.dragStartX = event.clientX
    this.dragStartWidth = this.previewPanelWidthPercent()
    this.containerWidth = this.layoutContainerRef?.nativeElement.offsetWidth ?? window.innerWidth
  }

  @HostListener('document:mousemove', ['$event'])
  onDocumentMousemove(event: MouseEvent): void {
    if (!this.isDraggingResizer) return
    const deltaX = this.dragStartX - event.clientX
    const deltaPercent = (deltaX / this.containerWidth) * 100
    const newWidth = Math.min(
      MAX_PANEL_WIDTH_PERCENT,
      Math.max(MIN_PANEL_WIDTH_PERCENT, this.dragStartWidth + deltaPercent)
    )
    this.previewPanelWidthPercent.set(newWidth)
  }

  @HostListener('document:mouseup')
  onDocumentMouseup(): void {
    if (!this.isDraggingResizer) return
    this.isDraggingResizer = false
    this.preferences.setMissionPreviewPanelWidth(this.previewPanelWidthPercent())
  }

  // ── Global SSE management ───────────────────────────────────────────────────────────────────

  private connectGlobalSse(retryDelayMs = 5_000): void {
    this.disconnectGlobalSse()

    const es = new EventSource('/api/event-stream')
    this.globalEventSource = es

    es.onmessage = (evt) => {
      try {
        const raw = JSON.parse(evt.data)
        const event = buildCodayEvent(raw)
        if (!event) return

        const threadId = event.threadId
        if (!threadId) return

        if (event instanceof InviteEvent || event instanceof ChoiceEvent) {
          this.globalTaskService.applyStatusEvent(threadId, 'invite')
        } else if (event instanceof ThinkingEvent) {
          this.globalTaskService.applyStatusEvent(threadId, 'thinking')
        } else if (event instanceof ThreadUpdateEvent) {
          // A ThreadUpdateEvent means something changed (e.g. invite answered, name updated).
          // Refresh from backend to get the authoritative pendingInvite status.
          this.globalTaskService.refresh()
        }
      } catch {
        // ignore parse errors
      }
    }

    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED) {
        this.globalEventSource = null
        // Reconnect with exponential backoff, max 60s
        setTimeout(() => {
          if (!this.globalEventSource) {
            this.connectGlobalSse(Math.min(retryDelayMs * 2, 60_000))
          }
        }, retryDelayMs)
      }
    }
  }

  private disconnectGlobalSse(): void {
    if (this.globalEventSource) {
      this.globalEventSource.close()
      this.globalEventSource = null
    }
  }
}
