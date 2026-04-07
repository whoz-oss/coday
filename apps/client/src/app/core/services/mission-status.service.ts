import { computed, inject, Injectable } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { map } from 'rxjs/operators'
import { ThreadStateService } from './thread-state.service'
import { CodayService } from './coday.service'
import { InviteEventDefault, ThreadSummary } from '@coday/model'
import { IN_PROGRESS_THRESHOLD_MS, MISSION_STATUS_PRIORITY } from './mission.constants'

/**
 * Derived status of a thread viewed as a mission.
 *
 * Computed purely client-side from existing state — no new backend endpoint.
 * For the active thread (SSE connected), the status is real-time.
 * For inactive threads, it is derived heuristically from modifiedDate.
 */
export type MissionStatus = 'waiting-you' | 'in-progress' | 'done' | 'paused' | 'error'

export interface MissionThread {
  id: string
  name: string
  summary: string
  modifiedDate: string
  starring: string[]
  users: { userId: string }[]
  status: MissionStatus
  isActive: boolean // true = SSE currently connected to this thread
  worktreeProject?: string
}

@Injectable({
  providedIn: 'root',
})
export class MissionStatusService {
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
   * All root-level threads (no parentThreadId) enriched with a derived MissionStatus.
   * Reactive: updates whenever the thread list, thinking state, or invite event changes.
   */
  readonly missions = computed<MissionThread[]>(() => {
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
        const pDiff = MISSION_STATUS_PRIORITY[a.status] - MISSION_STATUS_PRIORITY[b.status]
        if (pDiff !== 0) return pDiff
        return a.modifiedDate > b.modifiedDate ? -1 : 1
      })
  })

  /**
   * Derive the status of a single thread.
   *
   * Rules (in priority order):
   * 1. Active thread + invite pending → waiting-you
   * 2. Active thread + thinking → in-progress
   * 3. Active thread (SSE connected, idle) → in-progress (assume agent still running)
   * 4. Recently modified (< 5 min) non-active thread → in-progress (heuristic)
   * 5. Thread has a non-empty summary → done
   * 6. Default → paused
   */
  private deriveStatus(
    thread: ThreadSummary,
    isActive: boolean,
    isThinking: boolean,
    hasInvite: boolean
  ): MissionStatus {
    if (isActive) {
      if (hasInvite) return 'waiting-you'
      if (isThinking) return 'in-progress'
      // Active SSE but idle — agent finished or waiting silently
      return thread.summary ? 'done' : 'paused'
    }

    // A thread with a summary is always done
    if (thread.summary) return 'done'
    // Non-active thread: heuristic from recency
    const ageMs = Date.now() - new Date(thread.modifiedDate).getTime()
    if (ageMs < IN_PROGRESS_THRESHOLD_MS) return 'in-progress'
    return 'paused'
  }
}
