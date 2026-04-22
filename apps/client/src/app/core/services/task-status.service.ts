import { computed, inject, Injectable } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { map } from 'rxjs/operators'
import { ThreadStateService } from './thread-state.service'
import { CodayService } from './coday.service'
import { InviteEventDefault, ThreadSummary } from '@coday/model'
import { IN_PROGRESS_THRESHOLD_MS, TASK_STATUS_PRIORITY } from './task.constants'

/**
 * Derived status of a thread viewed as a task.
 *
 * Computed purely client-side from existing state — no new backend endpoint.
 * For the active thread (SSE connected), the status is real-time.
 * For inactive threads, it is derived heuristically from modifiedDate.
 */
export type TaskStatus = 'waiting-you' | 'in-progress' | 'done' | 'paused' | 'error'

export interface TaskThread {
  id: string
  name: string
  summary: string
  modifiedDate: string
  starring: string[]
  users: { userId: string }[]
  status: TaskStatus
  isActive: boolean // true = SSE currently connected to this thread
  worktreeProject?: string
}

@Injectable({
  providedIn: 'root',
})
export class TaskStatusService {
  private readonly threadState = inject(ThreadStateService)
  private readonly codayService = inject(CodayService)

  private readonly threadList = toSignal(this.threadState.threadList$, { initialValue: [] as ThreadSummary[] })
  private readonly selectedThread = toSignal(this.threadState.selectedThread$, { initialValue: null })
  private readonly isThinking = toSignal(this.codayService.isThinking$, { initialValue: false })
  private readonly inviteEvent = toSignal(
    this.codayService.currentInviteEvent$.pipe(
      // InviteEventDefault is the main loop prompt — not a real user-facing question
      map((e) => (e?.invite === InviteEventDefault ? null : e))
    ),
    { initialValue: null }
  )

  /**
   * All root-level threads (no parentThreadId) enriched with a derived TaskStatus.
   * Reactive: updates whenever the thread list, thinking state, or invite event changes.
   */
  readonly tasks = computed<TaskThread[]>(() => {
    const threads = this.threadList() as ThreadSummary[]
    const activeThread = this.selectedThread() as { id: string } | null
    const thinking = this.isThinking()
    const invite = this.inviteEvent()

    return threads
      .filter((t) => !t.parentThreadId)
      .map((t) => {
        const isActive = !!activeThread && t.id === activeThread.id
        return {
          id: t.id,
          name: t.name,
          summary: t.summary,
          modifiedDate: t.modifiedDate,
          starring: t.starring ?? [],
          users: t.users ?? [],
          status: this.deriveStatus(t, isActive, thinking, !!invite),
          isActive,
          worktreeProject: t.worktreeProject,
        }
      })
      .sort((a, b) => {
        const pDiff = TASK_STATUS_PRIORITY[a.status] - TASK_STATUS_PRIORITY[b.status]
        if (pDiff !== 0) return pDiff
        return a.modifiedDate > b.modifiedDate ? -1 : 1
      })
  })

  /**
   * Derive the status of a single thread.
   *
   * Rules (in priority order):
   * 1. closedByUser -> done (manual close by user)
   * 2. pendingInvite from backend registry -> waiting-you
   * 3. Active thread + thinking -> in-progress
   * 4. Active thread, idle -> paused
   * 5. Non-active, modified recently (< IN_PROGRESS_THRESHOLD_MS) -> in-progress
   * 6. Default -> paused
   */
  private deriveStatus(thread: ThreadSummary, isActive: boolean, isThinking: boolean, _hasInvite: boolean): TaskStatus {
    const ageMs = Date.now() - new Date(thread.modifiedDate).getTime()

    // 1. Manually closed by user
    if (thread.closedByUser) return 'done'

    // 2. Backend registry is the single source of truth for pending invite status.
    if (thread.pendingInvite) return 'waiting-you'

    // 3. Active thread
    if (isActive) {
      if (isThinking) return 'in-progress'
      return 'paused'
    }

    // 4. Non-active thread: heuristic from recency
    if (ageMs < IN_PROGRESS_THRESHOLD_MS) return 'in-progress'
    return 'paused'
  }
}
