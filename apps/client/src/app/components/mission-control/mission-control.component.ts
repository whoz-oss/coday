import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { Router } from '@angular/router'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'

import { MissionCardComponent } from './mission-card/mission-card.component'
import { SidenavComponent } from '../sidenav/sidenav.component'
import { MissionStatus, MissionStatusService } from '../../core/services/mission-status.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { UserService } from '../../core/services/user.service'
import { ProjectEventStreamService } from '../../core/services/project-event-stream.service'

type FilterKey = 'all' | MissionStatus

interface FilterOption {
  key: FilterKey
  label: string
  icon: string
}

const FILTERS: FilterOption[] = [
  { key: 'all', label: 'All', icon: 'grid_view' },
  { key: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt' },
  { key: 'in-progress', label: 'In progress', icon: 'pending' },
  { key: 'done', label: 'Done', icon: 'check_circle' },
]

@Component({
  selector: 'app-mission-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MissionCardComponent, SidenavComponent, MatIconModule, MatButtonModule, MatProgressSpinnerModule],
  templateUrl: './mission-control.component.html',
  styleUrl: './mission-control.component.scss',
})
export class MissionControlComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly projectState = inject(ProjectStateService)
  private readonly threadState = inject(ThreadStateService)
  private readonly threadApi = inject(ThreadApiService)
  private readonly missionStatus = inject(MissionStatusService)
  private readonly userService = inject(UserService)
  private readonly destroyRef = inject(DestroyRef)
  // Injected to ensure the service is instantiated and SSE connection is active
  protected readonly _projectEventStream = inject(ProjectEventStreamService)

  protected readonly filters = FILTERS
  protected readonly activeFilter = signal<FilterKey>('all')
  protected readonly isCreating = signal(false)

  protected readonly isLoadingList = toSignal(this.threadState.isLoadingThreadList$, { initialValue: false })
  protected readonly username = toSignal(this.userService.username$, { initialValue: null })

  protected readonly allMissions = this.missionStatus.missions

  protected readonly filteredMissions = computed(() => {
    const filter = this.activeFilter()
    const missions = this.allMissions()
    if (filter === 'all') return missions
    return missions.filter((m) => m.status === filter)
  })

  /** Count per filter for badges */
  protected readonly filterCounts = computed(() => {
    const missions = this.allMissions()
    return {
      all: missions.length,
      'waiting-you': missions.filter((m) => m.status === 'waiting-you').length,
      'in-progress': missions.filter((m) => m.status === 'in-progress').length,
      done: missions.filter((m) => m.status === 'done').length,
    } as Record<FilterKey, number>
  })

  /** Groups for "All" view: sections by status (only non-empty) */
  protected readonly missionGroups = computed(() => {
    const missions = this.allMissions()
    const groups: { status: MissionStatus; label: string; icon: string; missions: typeof missions }[] = [
      { status: 'waiting-you', label: 'Waiting for you', icon: 'mark_unread_chat_alt', missions: [] },
      { status: 'in-progress', label: 'In progress', icon: 'pending', missions: [] },
      { status: 'done', label: 'Done', icon: 'check_circle', missions: [] },
      { status: 'paused', label: 'Paused', icon: 'pause_circle', missions: [] },
      { status: 'error', label: 'Error', icon: 'error', missions: [] },
    ]
    for (const mission of missions) {
      const group = groups.find((g) => g.status === mission.status)
      if (group) group.missions.push(mission)
    }
    return groups.filter((g) => g.missions.length > 0)
  })

  ngOnInit(): void {
    // Ensure username is loaded for star operations
    this.userService
      .fetchCurrentUser()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ error: (err) => console.warn('[MISSION-CONTROL] Could not fetch user:', err) })
  }

  setFilter(key: FilterKey): void {
    this.activeFilter.set(key)
  }

  createMission(): void {
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
          console.error('[MISSION-CONTROL] Failed to create thread:', err)
          this.isCreating.set(false)
        },
      })
  }

  onStopRequested(threadId: string): void {
    this.threadApi
      .stopThread(threadId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err) => console.error('[MISSION-CONTROL] Failed to stop thread:', err),
      })
  }

  onDeleteRequested(threadId: string): void {
    this.threadApi
      .deleteThread(threadId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.threadState.refreshThreadList(),
        error: (err) => console.error('[MISSION-CONTROL] Failed to delete thread:', err),
      })
  }

  onStarToggled(threadId: string): void {
    const currentUsername = this.username()
    if (!currentUsername) return

    const mission = this.allMissions().find((m) => m.id === threadId)
    if (!mission) return

    const isStarred = mission.starring.includes(currentUsername)
    const op$ = isStarred ? this.threadApi.unstarThread(threadId) : this.threadApi.starThread(threadId)

    // Optimistic update
    this.threadState.updateStarLocal(threadId, !isStarred, currentUsername)

    op$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      error: (err) => {
        console.error('[MISSION-CONTROL] Failed to toggle star:', err)
        this.threadState.refreshThreadList()
      },
    })
  }
}
