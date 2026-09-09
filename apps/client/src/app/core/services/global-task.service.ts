import { inject, Injectable, signal } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { forkJoin, of } from 'rxjs'
import { timeout } from 'rxjs/operators'
import { ThreadSummary } from '@coday/model'
import { ThreadApiService } from './thread-api.service'
import { ProjectApiService } from './project-api.service'
import { TaskStatus, TaskThread } from './task-status.service'
import { IN_PROGRESS_THRESHOLD_MS, TASK_STATUS_PRIORITY } from './task.constants'

/** A TaskThread enriched with the project it belongs to */
export interface GlobalTaskThread extends TaskThread {
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
export class GlobalTaskService {
  private readonly http = inject(HttpClient)
  private readonly threadApi = inject(ThreadApiService)
  private readonly projectApi = inject(ProjectApiService)

  readonly isLoading = signal(false)
  readonly tasks = signal<GlobalTaskThread[]>([])

  /**
   * Fetch threads from all accessible projects and compute task statuses.
   * Results are stored in the `tasks` signal.
   */
  refresh(): void {
    this.isLoading.set(true)

    this.threadApi
      .listAllThreads()
      .pipe(timeout(15_000))
      .subscribe({
        next: (threads) => {
          const allTasks: GlobalTaskThread[] = threads
            .filter((t) => !t.parentThreadId)
            .map((thread) => ({
              id: thread.id,
              name: thread.name,
              summary: thread.summary,
              modifiedDate: thread.modifiedDate,
              starring: thread.starring ?? [],
              users: thread.users ?? [],
              isActive: false,
              // Backend pendingInvite / closedByUser are the sources of truth
              status: this.deriveStatus(thread),
              projectId: thread.projectId,
              worktreeProject: thread.worktreeProject,
            }))

          this.tasks.set(this.sorted(allTasks))
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
        },
      })
  }

  /**
   * Apply a live status event received from the project-level SSE.
   * Updates the task status in place without a full refresh.
   *
   * @param threadId  The thread that emitted the event
   * @param kind      'invite' => waiting-you  |  'thinking' => in-progress
   */
  applyStatusEvent(threadId: string, kind: 'invite' | 'thinking'): void {
    const newStatus: TaskStatus = kind === 'invite' ? 'waiting-you' : 'in-progress'
    this.tasks.update((tasks) => this.sorted(tasks.map((t) => (t.id === threadId ? { ...t, status: newStatus } : t))))
  }

  /**
   * Close a worktree task: stop agent, delete thread, remove worktree and project.
   */
  closeTask(projectId: string, threadId: string): void {
    this.tasks.update((tasks) => tasks.filter((t) => t.id !== threadId))
    this.http.delete(`/api/projects/${projectId}/missions/${threadId}`).subscribe({
      next: () => this.refresh(),
      error: (err) => {
        console.error('[GLOBAL-TC] Failed to close task:', err)
        this.refresh()
      },
    })
  }

  /**
   * Stop a thread's execution.
   */
  stopThread(projectId: string, threadId: string): void {
    this.http.post(`/api/projects/${projectId}/threads/${threadId}/stop`, {}).subscribe({
      error: (err) => console.error('[GLOBAL-TC] Failed to stop thread:', err),
    })
  }

  /**
   * Delete a thread: remove it optimistically from the local list,
   * then refresh from the server to stay in sync.
   */
  deleteThread(projectId: string, threadId: string): void {
    // Optimistic remove -- instant UI feedback, no loading spinner
    this.tasks.update((tasks) => tasks.filter((t) => t.id !== threadId))

    this.http.delete(`/api/projects/${projectId}/threads/${threadId}`).subscribe({
      next: () => this.refresh(),
      error: (err) => {
        console.error('[GLOBAL-TC] Failed to delete thread:', err)
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
      error: (err) => console.error('[GLOBAL-TC] Failed to toggle star:', err),
    })
  }

  /**
   * Mark a thread as done (sets closedByUser on the backend).
   */
  markTaskDone(projectId: string, threadId: string): void {
    this.http.post(`/api/projects/${projectId}/threads/${threadId}/done`, {}).subscribe({
      next: () => this.refresh(),
      error: (err) => console.error('[GLOBAL-TC] Failed to mark task as done:', err),
    })
  }

  /**
   * Mark multiple paused threads as done in a single batch per project.
   * Threads may belong to different projects — requests are grouped and sent in parallel.
   *
   * @param tasks Array of { id, projectId } pairs — typically the selected paused tasks
   */
  markTasksBatchDone(tasks: { id: string; projectId: string }[]): void {
    if (tasks.length === 0) return

    // Group thread IDs by project
    const byProject = new Map<string, string[]>()
    for (const { id, projectId } of tasks) {
      const ids = byProject.get(projectId) ?? []
      ids.push(id)
      byProject.set(projectId, ids)
    }

    // Optimistic update: mark them as done locally
    const doneIds = new Set(tasks.map((t) => t.id))
    this.tasks.update((all) =>
      this.sorted(all.map((t) => (doneIds.has(t.id) ? { ...t, status: 'done' as TaskStatus } : t)))
    )

    // Fire one batch request per project in parallel
    const requests = [...byProject.entries()].map(([projectId, threadIds]) =>
      this.projectApi.markThreadsBatchDone(projectId, threadIds)
    )

    forkJoin(requests.length > 0 ? requests : [of(null)]).subscribe({
      next: () => this.refresh(),
      error: (err) => {
        console.error('[GLOBAL-TC] Failed to batch-mark tasks as done:', err)
        this.refresh()
      },
    })
  }

  /**
   * Mark a thread as active again (clears closedByUser on the backend).
   */
  markTaskActive(projectId: string, threadId: string): void {
    this.http.delete(`/api/projects/${projectId}/threads/${threadId}/done`).subscribe({
      next: () => this.refresh(),
      error: (err) => console.error('[GLOBAL-TC] Failed to mark task as active:', err),
    })
  }

  // Private helpers

  /**
   * Derive task status for a thread.
   * Rules (in priority order):
   * 1. closedByUser -> done
   * 2. pendingInvite -> waiting-you
   * 3. Recently modified -> in-progress
   * 4. Default -> paused
   */
  private deriveStatus(thread: ThreadSummary): TaskStatus {
    // 1. Manually closed by user
    if (thread.closedByUser) return 'done'
    // 2. Backend registry is authoritative for pending invite
    if (thread.pendingInvite) return 'waiting-you'
    const ageMs = Date.now() - new Date(thread.modifiedDate).getTime()
    if (ageMs < IN_PROGRESS_THRESHOLD_MS) return 'in-progress'
    return 'paused'
  }

  private sorted(tasks: GlobalTaskThread[]): GlobalTaskThread[] {
    return [...tasks].sort((a, b) => {
      const pDiff = TASK_STATUS_PRIORITY[a.status] - TASK_STATUS_PRIORITY[b.status]
      if (pDiff !== 0) return pDiff
      return a.modifiedDate > b.modifiedDate ? -1 : 1
    })
  }
}
