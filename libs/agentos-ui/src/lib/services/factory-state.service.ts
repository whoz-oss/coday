import { inject, Injectable, OnDestroy, signal } from '@angular/core'
import { interval, Subscription } from 'rxjs'
import { switchMap, take } from 'rxjs/operators'
import { FactoryApiService, FactoryRunDetail, FactoryRunSummary } from './factory-api.service'

const DETAIL_POLL_INTERVAL_MS = 4000
const LIST_POLL_INTERVAL_MS = 10_000

/** Reactive state for the Factory run list in one namespace. */
@Injectable({ providedIn: 'root' })
export class FactoryStateService implements OnDestroy {
  private readonly api = inject(FactoryApiService)

  readonly namespaceId = signal<string | null>(null)
  readonly runs = signal<FactoryRunSummary[]>([])
  readonly loading = signal(false)
  readonly error = signal<string | null>(null)
  readonly selectedRun = signal<FactoryRunDetail | null>(null)
  readonly detailLoading = signal(false)
  readonly detailError = signal<string | null>(null)
  readonly namespaceMismatch = signal(false)
  private detailRequestId = 0
  private streamSubscription: Subscription | null = null
  private streamedRunId: string | null = null
  private detailPollSubscription: Subscription | null = null
  private listPollSubscription: Subscription | null = null
  /** Tracks the namespace for which list-level polling is active. */
  private listPollNamespaceId: string | null = null

  load(namespaceId: string): void {
    this.namespaceId.set(namespaceId)
    this.loading.set(true)
    this.error.set(null)
    this.runs.set([])
    this.stopListPoll()

    this.api.listRuns(namespaceId).subscribe({
      next: (runs) => {
        this.runs.set(runs)
        this.loading.set(false)
        this.manageListPoll(namespaceId)
      },
      error: () => {
        this.error.set('Factory runs could not be loaded. Please try again.')
        this.loading.set(false)
      },
    })
  }

  loadDetail(runId: string, namespaceId: string): void {
    const requestId = ++this.detailRequestId
    this.selectedRun.set(null)
    this.detailError.set(null)
    this.namespaceMismatch.set(false)
    this.detailLoading.set(true)

    this.api.getRun(runId).subscribe({
      next: (run) => {
        if (requestId !== this.detailRequestId) return
        this.detailLoading.set(false)
        if (run.namespaceId !== namespaceId) {
          this.namespaceMismatch.set(true)
          return
        }
        this.selectedRun.set(run)
        this.projectDetailIntoList(run)
        if (run.status === 'running') {
          this.startStream(run.runId, namespaceId)
          this.startDetailPoll(run.runId, namespaceId)
        } else {
          this.stopStream()
          this.stopDetailPoll()
        }
        // Re-evaluate list polling — a run that just finished may have been the last running one.
        this.manageListPoll(namespaceId)
      },
      error: (error: { status?: number }) => {
        if (requestId !== this.detailRequestId) return
        this.detailLoading.set(false)
        this.detailError.set(
          error.status === 404 ? 'This Factory run no longer exists.' : 'Factory run details could not be loaded.'
        )
      },
    })
  }

  clearDetail(): void {
    this.stopStream()
    this.stopDetailPoll()
    this.detailRequestId++
    this.selectedRun.set(null)
    this.detailLoading.set(false)
    this.detailError.set(null)
    this.namespaceMismatch.set(false)
  }

  // ---------------------------------------------------------------------------
  // Detail streaming / polling
  // ---------------------------------------------------------------------------

  private startStream(runId: string, namespaceId: string): void {
    if (this.streamedRunId === runId && this.streamSubscription) return
    this.stopStream()
    this.streamedRunId = runId
    this.streamSubscription = this.api.streamRun(runId).subscribe({
      next: () => this.refreshDetailQuiet(runId, namespaceId),
      error: () => {
        this.stopStream()
        this.stopDetailPoll()
      },
    })
  }

  private stopStream(): void {
    this.streamSubscription?.unsubscribe()
    this.streamSubscription = null
    this.streamedRunId = null
  }

  /**
   * Starts a low-frequency (~4 s) polling fallback for the currently running run.
   * Uses switchMap on an interval so a slow response is automatically cancelled
   * when the next tick fires, preventing response pile-up.
   * Guards against duplicate instances: a second call for the same runId is a no-op.
   */
  private startDetailPoll(runId: string, namespaceId: string): void {
    // Already polling the same run — no-op.
    if (this.detailPollSubscription && !this.detailPollSubscription.closed) return
    this.stopDetailPoll()

    const capturedRequestId = this.detailRequestId
    this.detailPollSubscription = interval(DETAIL_POLL_INTERVAL_MS)
      .pipe(switchMap(() => this.api.getRun(runId)))
      .subscribe({
        next: (run) => {
          // Discard if a newer loadDetail() has taken over.
          if (capturedRequestId !== this.detailRequestId) {
            this.stopDetailPoll()
            return
          }
          if (run.namespaceId !== namespaceId) return
          this.selectedRun.set(run)
          this.projectDetailIntoList(run)
          if (run.status !== 'running') {
            this.stopDetailPoll()
            this.stopStream()
            this.manageListPoll(namespaceId)
          }
        },
        error: () => this.stopDetailPoll(),
      })
  }

  private stopDetailPoll(): void {
    this.detailPollSubscription?.unsubscribe()
    this.detailPollSubscription = null
  }

  /**
   * Fetches the latest run detail and merges it into state without touching
   * loading/error flags.  Used by SSE events to avoid clobbering UI state.
   * Stale responses (superseded detailRequestId) are discarded.
   */
  private refreshDetailQuiet(runId: string, namespaceId: string): void {
    const capturedId = this.detailRequestId
    this.api.getRun(runId).subscribe({
      next: (run) => {
        if (capturedId !== this.detailRequestId) return
        if (run.namespaceId !== namespaceId) return
        this.selectedRun.set(run)
        this.projectDetailIntoList(run)
        if (run.status !== 'running') {
          this.stopStream()
          this.stopDetailPoll()
          this.manageListPoll(namespaceId)
        }
      },
      error: () => {
        /* silent — SSE will retry */
      },
    })
  }

  // ---------------------------------------------------------------------------
  // List-level polling (10 s, active only while ≥1 running run exists)
  // ---------------------------------------------------------------------------

  /**
   * Starts or stops list-level polling based on whether the current run list
   * contains at least one `running` entry.
   *
   * Safe to call multiple times — will not create duplicate subscriptions.
   * A stale namespace check prevents responses from a previous namespace
   * from overwriting state after a namespace switch.
   */
  private manageListPoll(namespaceId: string): void {
    const hasRunning = this.runs().some((r) => r.status === 'running')

    if (!hasRunning) {
      // Stop if the last running run has finished or there were none.
      if (this.listPollNamespaceId === namespaceId) {
        this.stopListPoll()
      }
      return
    }

    // Already polling for this namespace — no-op (avoid duplicate subscriptions).
    if (this.listPollSubscription && !this.listPollSubscription.closed && this.listPollNamespaceId === namespaceId) {
      return
    }

    this.stopListPoll()
    this.listPollNamespaceId = namespaceId

    this.listPollSubscription = interval(LIST_POLL_INTERVAL_MS)
      .pipe(switchMap(() => this.api.listRuns(namespaceId).pipe(take(1))))
      .subscribe({
        next: (runs) => {
          // Discard if the service has moved to a different namespace.
          if (this.namespaceId() !== namespaceId) {
            this.stopListPoll()
            return
          }
          this.mergeListUpdate(runs)
          if (!runs.some((r) => r.status === 'running')) {
            this.stopListPoll()
          }
        },
        error: () => this.stopListPoll(),
      })
  }

  private stopListPoll(): void {
    this.listPollSubscription?.unsubscribe()
    this.listPollSubscription = null
    this.listPollNamespaceId = null
  }

  /**
   * Merges a fresh server list into the current runs signal.
   * - New entries are appended.
   * - Existing entries are updated in-place (preserving order).
   * Uses a minimal equality check to avoid unnecessary signal writes.
   */
  private mergeListUpdate(fresh: FactoryRunSummary[]): void {
    const current = this.runs()
    const freshById = new Map(fresh.map((r) => [r.runId, r]))

    // Update or keep existing entries, append new ones.
    const currentIds = new Set(current.map((r) => r.runId))
    const updated = current.map((r) => freshById.get(r.runId) ?? r)
    const added = fresh.filter((r) => !currentIds.has(r.runId))

    this.runs.set([...updated, ...added])
  }

  /**
   * Projects summary fields from a freshly loaded FactoryRunDetail into the
   * matching list entry.  Called after every detail fetch (initial, poll, SSE).
   * Does nothing if the run is not in the list yet (e.g. brand-new launch).
   */
  private projectDetailIntoList(detail: FactoryRunDetail): void {
    const current = this.runs()
    const idx = current.findIndex((r) => r.runId === detail.runId)
    if (idx === -1) return

    const existing = current[idx]! // findIndex returned a valid index
    const updated: FactoryRunSummary = {
      runId: existing.runId,
      workflow: detail.workflow ?? existing.workflow,
      namespaceId: detail.namespaceId ?? existing.namespaceId,
      status: detail.status,
      startedAt: detail.startedAt,
      endedAt: detail.endedAt,
      durationMs: detail.durationMs,
      phaseCount: detail.phaseCount,
      context: detail.context,
    }

    // Avoid a signal write when nothing changed.
    const changed =
      existing.status !== updated.status ||
      existing.startedAt !== updated.startedAt ||
      existing.endedAt !== updated.endedAt ||
      existing.durationMs !== updated.durationMs ||
      existing.phaseCount !== updated.phaseCount

    if (!changed) return

    const next = [...current]
    next[idx] = updated
    this.runs.set(next)
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  ngOnDestroy(): void {
    this.stopStream()
    this.stopDetailPoll()
    this.stopListPoll()
  }

  refresh(): void {
    const namespaceId = this.namespaceId()
    if (namespaceId) this.load(namespaceId)
  }

  clear(): void {
    this.namespaceId.set(null)
    this.runs.set([])
    this.loading.set(false)
    this.error.set(null)
    this.stopListPoll()
    this.clearDetail()
  }
}
