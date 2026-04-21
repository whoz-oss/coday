import { inject, Injectable, signal } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { timeout } from 'rxjs/operators'
import { ThreadSummary } from '@coday/model'
import { ThreadApiService } from './thread-api.service'
import { MissionStatus, MissionThread } from './mission-status.service'
import { IN_PROGRESS_THRESHOLD_MS, MISSION_STATUS_PRIORITY } from './mission.constants'

/** A MissionThread enriched with the project it belongs to */
export interface GlobalMissionThread extends MissionThread {
  projectId: string
}

/**
 * Service that aggregates threads from ALL accessible projects.
 *
 * This is a provisional implementation: it calls each project's thread list
 * endpoint separately and merges the results. A future backend endpoint
 * could replace this with a single call.
 *
 * Live status updates (waiting-you / in-progress) are fed externally via
 * `applyStatusEvent()`, called by the component when it receives events
 * from ProjectEventStreamService.
 */
@Injectable({
  providedIn: 'root',
})
export class GlobalMissionService {
  private readonly http = inject(HttpClient)
  private readonly threadApi = inject(ThreadApiService)

  readonly isLoading = signal(false)
  readonly missions = signal<GlobalMissionThread[]>([])

  /**
   * Fetch threads from all accessible projects and compute mission statuses.
   * Results are stored in the `missions` signal.
   */
  refresh(): void {
    this.isLoading.set(true)

    this.threadApi
      .listAllThreads()
      .pipe(timeout(15_000))
      .subscribe({
        next: (threads) => {
          const allMissions: GlobalMissionThread[] = threads
            .filter((t) => !t.parentThreadId)
            .map((thread) => ({
              id: thread.id,
              name: thread.name,
              summary: thread.summary,
              modifiedDate: thread.modifiedDate,
              starring: thread.starring ?? [],
              users: thread.users ?? [],
              isActive: false,
              // Backend pendingInvite is the single source of truth — no live status caching
              status: this.deriveStatus(thread),
              projectId: thread.projectId,
              worktreeProject: thread.worktreeProject,
            }))

          this.missions.set(this.sorted(allMissions))
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
        },
      })
  }

  /**
   * Apply a live status event received from the project-level SSE.
   * Updates the mission status in place without a full refresh.
   *
   * @param threadId  The thread that emitted the event
   * @param kind      'invite' => waiting-you  |  'thinking' => in-progress
   */
  applyStatusEvent(threadId: string, kind: 'invite' | 'thinking'): void {
    const newStatus: MissionStatus = kind === 'invite' ? 'waiting-you' : 'in-progress'
    this.missions.update((missions) =>
      this.sorted(missions.map((m) => (m.id === threadId ? { ...m, status: newStatus } : m)))
    )
  }

  /**
   * Close a worktree mission: stop agent, delete thread, remove worktree and project.
   */
  closeMission(projectId: string, threadId: string): void {
    this.missions.update((missions) => missions.filter((m) => m.id !== threadId))
    this.http.delete(`/api/projects/${projectId}/missions/${threadId}`).subscribe({
      next: () => this.refresh(),
      error: (err) => {
        console.error('[GLOBAL-MC] Failed to close mission:', err)
        this.refresh()
      },
    })
  }

  /**
   * Stop a thread's execution.
   */
  stopThread(projectId: string, threadId: string): void {
    this.http.post(`/api/projects/${projectId}/threads/${threadId}/stop`, {}).subscribe({
      error: (err) => console.error('[GLOBAL-MC] Failed to stop thread:', err),
    })
  }

  /**
   * Delete a thread: remove it optimistically from the local list,
   * then refresh from the server to stay in sync.
   */
  deleteThread(projectId: string, threadId: string): void {
    // Optimistic remove -- instant UI feedback, no loading spinner
    this.missions.update((missions) => missions.filter((m) => m.id !== threadId))

    this.http.delete(`/api/projects/${projectId}/threads/${threadId}`).subscribe({
      next: () => this.refresh(),
      error: (err) => {
        console.error('[GLOBAL-MC] Failed to delete thread:', err)
        // Rollback: restore the list from server
        this.refresh()
      },
    })
  }

  /**
   * Toggle star on a thread.
   * Applies an optimistic update immediately, then confirms via refresh.
   * On error, rolls back by refreshing from the server.
   */
  toggleStar(projectId: string, threadId: string, isStarred: boolean): void {
    const url = `/api/projects/${projectId}/threads/${threadId}/star`
    const req$ = isStarred ? this.http.delete(url) : this.http.post(url, {})
    req$.subscribe({
      next: () => this.refresh(),
      error: (err) => console.error('[GLOBAL-MC] Failed to toggle star:', err),
    })
  }

  // Private helpers

  /**
   * Derive mission status for a thread.
   * The backend in-memory registry (exposed via thread.pendingInvite) is the
   * single source of truth for waiting-you. All other statuses are heuristic.
   */
  private deriveStatus(thread: ThreadSummary): MissionStatus {
    // Backend registry is authoritative for pending invite
    if (thread.pendingInvite) return 'waiting-you'
    if (thread.summary) return 'done'
    const hasRun = (thread.price ?? 0) > 0
    const ageMs = Date.now() - new Date(thread.modifiedDate).getTime()
    if (ageMs < IN_PROGRESS_THRESHOLD_MS) return 'in-progress'
    if (hasRun) return 'done'
    return 'paused'
  }

  private sorted(missions: GlobalMissionThread[]): GlobalMissionThread[] {
    return [...missions].sort((a, b) => {
      const pDiff = MISSION_STATUS_PRIORITY[a.status] - MISSION_STATUS_PRIORITY[b.status]
      if (pDiff !== 0) return pDiff
      return a.modifiedDate > b.modifiedDate ? -1 : 1
    })
  }
}
