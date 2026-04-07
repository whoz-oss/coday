import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, OnInit, signal } from '@angular/core'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatTooltipModule } from '@angular/material/tooltip'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { buildCodayEvent, ChoiceEvent, InviteEvent, ThinkingEvent } from '@coday/model'
import { MissionCardComponent } from '../mission-control/mission-card/mission-card.component'
import { NewMissionDialogComponent } from '../new-mission-dialog/new-mission-dialog.component'
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component'
import { GlobalMissionService } from '../../core/services/global-mission.service'
import { MissionStatus } from '../../core/services/mission-status.service'

type FilterKey = 'all' | MissionStatus

const STATUS_GROUPS: { status: MissionStatus; label: string; icon: string }[] = [
  { status: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt' },
  { status: 'in-progress', label: 'In progress', icon: 'pending' },
  { status: 'done', label: 'Done', icon: 'check_circle' },
  { status: 'paused', label: 'Paused', icon: 'pause_circle' },
  { status: 'error', label: 'Error', icon: 'error' },
]

const FILTERS: { key: FilterKey; label: string; icon: string }[] = [
  { key: 'all', label: 'All', icon: 'grid_view' },
  { key: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt' },
  { key: 'in-progress', label: 'In progress', icon: 'pending' },
  { key: 'done', label: 'Done', icon: 'check_circle' },
]

/**
 * Global Mission Control dashboard displayed on the project selection page.
 *
 * Aggregates threads from ALL accessible projects using GlobalMissionService,
 * which calls each project's thread list endpoint in parallel.
 *
 * For live status updates (waiting-you / in-progress), opens one project-level
 * SSE connection per project found in the mission list. This avoids the need
 * for a per-thread SSE connection.
 */
@Component({
  selector: 'app-global-mission-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MissionCardComponent, MatIconModule, MatButtonModule, MatTooltipModule, MatProgressSpinnerModule],
  templateUrl: './global-mission-control.component.html',
  styleUrl: './global-mission-control.component.scss',
})
export class GlobalMissionControlComponent implements OnInit {
  private readonly globalMissionService = inject(GlobalMissionService)
  private readonly destroyRef = inject(DestroyRef)
  private readonly dialog = inject(MatDialog)

  /** One EventSource per project, keyed by project name */
  private readonly projectSseMap = new Map<string, EventSource>()

  protected readonly filters = FILTERS
  protected readonly activeFilter = signal<FilterKey>('all')
  protected readonly activeProject = signal<string | null>(null)

  protected readonly isLoading = this.globalMissionService.isLoading
  protected readonly allMissions = this.globalMissionService.missions

  /** Unique project names with their mission counts */
  protected readonly projectSummaries = computed(() => {
    const missions = this.allMissions()
    const countByProject = new Map<string, number>()
    for (const m of missions) {
      countByProject.set(m.projectId, (countByProject.get(m.projectId) ?? 0) + 1)
    }
    return [...countByProject.entries()]
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => a.name.localeCompare(b.name))
  })

  /** Missions filtered by active project and active status filter */
  protected readonly filteredMissions = computed(() => {
    let missions = this.allMissions()

    const project = this.activeProject()
    if (project) {
      missions = missions.filter((m) => m.projectId === project)
    }

    const filter = this.activeFilter()
    if (filter !== 'all') {
      missions = missions.filter((m) => m.status === filter)
    }

    return missions
  })

  /** Counts for filter badges (after project filter, before status filter) */
  protected readonly filterCounts = computed((): Record<FilterKey, number> => {
    let missions = this.allMissions()
    const project = this.activeProject()
    if (project) missions = missions.filter((m) => m.projectId === project)

    return {
      all: missions.length,
      'waiting-you': missions.filter((m) => m.status === 'waiting-you').length,
      'in-progress': missions.filter((m) => m.status === 'in-progress').length,
      done: missions.filter((m) => m.status === 'done').length,
      paused: missions.filter((m) => m.status === 'paused').length,
      error: missions.filter((m) => m.status === 'error').length,
    }
  })

  /** Groups for the "All" status view */
  protected readonly missionGroups = computed(() => {
    const missions = this.filteredMissions()
    return STATUS_GROUPS.map((g) => ({
      ...g,
      missions: missions.filter((m) => m.status === g.status),
    })).filter((g) => g.missions.length > 0)
  })

  constructor() {
    // When the mission list changes, open/close SSE connections for each project
    effect(() => {
      const projectNames = new Set(this.allMissions().map((m) => m.projectId))
      this.syncProjectSseConnections(projectNames)
    })

    // Close all SSE connections when the component is destroyed
    this.destroyRef.onDestroy(() => this.closeAllProjectSse())
  }

  ngOnInit(): void {
    this.globalMissionService.refresh()
  }

  protected setFilter(key: FilterKey): void {
    this.activeFilter.set(key)
  }

  protected setProject(name: string | null): void {
    this.activeProject.set(name)
    this.activeFilter.set('all')
  }

  protected refresh(): void {
    this.globalMissionService.refresh()
  }

  protected openNewMissionDialog(): void {
    const ref = this.dialog.open(NewMissionDialogComponent, {
      width: '500px',
      disableClose: false,
    })
    ref.afterClosed().subscribe((result: { threadId: string; projectId: string } | null) => {
      if (result) {
        // Open SSE for the new project immediately (worktree or not)
        if (!this.projectSseMap.has(result.projectId)) {
          this.openProjectSse(result.projectId)
        }
        // Refresh the mission list — retry a few times to catch the new thread
        setTimeout(() => this.globalMissionService.refresh(), 300)
        setTimeout(() => this.globalMissionService.refresh(), 1500)
      }
    })
  }

  protected onStopRequested(threadId: string, projectId: string): void {
    this.globalMissionService.stopThread(projectId, threadId)
  }

  protected onDeleteRequested(threadId: string, projectId: string): void {
    this.globalMissionService.deleteThread(projectId, threadId)
  }

  protected onStarToggled(threadId: string, projectId: string): void {
    const mission = this.allMissions().find((m) => m.id === threadId)
    if (!mission) return
    const isStarred = mission.starring.length > 0
    this.globalMissionService.toggleStar(projectId, threadId, isStarred)
  }

  protected onCloseMissionRequested(threadId: string, projectId: string): void {
    const mission = this.allMissions().find((m) => m.id === threadId)
    const worktreeName = mission?.worktreeProject ?? projectId
    const ref = this.dialog.open(ConfirmDialogComponent, {
      width: '420px',
      data: {
        title: 'Close mission',
        message: `This will stop the agent, delete the thread and remove the worktree project "${worktreeName}" from disk. This cannot be undone.`,
        confirmLabel: 'Close mission',
        cancelLabel: 'Cancel',
      },
    })
    ref.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {
        this.globalMissionService.closeMission(projectId, threadId)
      }
    })
  }

  // ── Project-level SSE management ─────────────────────────────────────────────────

  private syncProjectSseConnections(activeProjects: Set<string>): void {
    // Open new connections for projects not yet tracked
    for (const projectName of activeProjects) {
      if (!this.projectSseMap.has(projectName)) {
        this.openProjectSse(projectName)
      }
    }
    // Close connections for projects no longer in the list
    for (const [projectName, es] of this.projectSseMap) {
      if (!activeProjects.has(projectName)) {
        es.close()
        this.projectSseMap.delete(projectName)
      }
    }
  }

  private openProjectSse(projectName: string, retryDelayMs = 5_000): void {
    const url = `/api/projects/${encodeURIComponent(projectName)}/event-stream`
    const es = new EventSource(url)

    es.onmessage = (evt) => {
      try {
        const raw = JSON.parse(evt.data)
        const event = buildCodayEvent(raw)
        if (!event) return

        const threadId = event.threadId
        if (!threadId) return

        if (event instanceof InviteEvent || event instanceof ChoiceEvent) {
          this.globalMissionService.applyStatusEvent(threadId, 'invite')
        } else if (event instanceof ThinkingEvent) {
          this.globalMissionService.applyStatusEvent(threadId, 'thinking')
        }
      } catch {
        // ignore parse errors
      }
    }

    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED && this.projectSseMap.has(projectName)) {
        es.close()
        this.projectSseMap.delete(projectName)
        setTimeout(() => {
          if (this.projectSseMap.has(projectName) === false) {
            this.openProjectSse(projectName, Math.min(retryDelayMs * 2, 60_000))
          }
        }, retryDelayMs)
      }
    }

    this.projectSseMap.set(projectName, es)
  }

  private closeAllProjectSse(): void {
    for (const es of this.projectSseMap.values()) {
      es.close()
    }
    this.projectSseMap.clear()
  }
}
