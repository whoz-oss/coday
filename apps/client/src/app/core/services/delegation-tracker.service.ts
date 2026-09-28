import { Injectable, OnDestroy, signal, inject } from '@angular/core'
import { Subject } from 'rxjs'
import { takeUntil, filter } from 'rxjs/operators'
import { DelegationStatusEvent, DelegationStatus } from '@coday/model'
import { CodayService } from './coday.service'

/**
 * Tracks the number of delegations (sub-threads) currently running.
 *
 * Subscribes to {@link CodayService#subThreadEvents$} and filters for
 * {@link DelegationStatusEvent} instances. Maintains a flat map of sub-thread ID
 * to last known status, and exposes the count of entries whose status is 'running'.
 *
 * Known limitation: {@link DelegationStatusEvent} is transient and never persisted
 * in thread history. A page reload while delegations are in flight resets the counter
 * to zero — this is an accepted trade-off; do not attempt to reconstruct this state.
 */
@Injectable({ providedIn: 'root' })
export class DelegationTrackerService implements OnDestroy {
  private readonly codayService = inject(CodayService)
  private readonly destroy$ = new Subject<void>()

  /** Last known status keyed by sub-thread ID. */
  private readonly statusMap = new Map<string, DelegationStatus>()

  private readonly _runningCount = signal<number>(0)

  /** Number of delegations whose last known status is 'running'. */
  readonly runningCount = this._runningCount.asReadonly()

  constructor() {
    this.codayService.subThreadEvents$
      .pipe(
        filter((event): event is DelegationStatusEvent => event instanceof DelegationStatusEvent),
        takeUntil(this.destroy$)
      )
      .subscribe((event) => {
        this.statusMap.set(event.threadId!, event.status)
        this.recount()
      })
  }

  /**
   * Clears the tracker. Call this whenever the active thread changes so that
   * counts from a previous thread do not bleed into the new one.
   */
  reset(): void {
    this.statusMap.clear()
    this._runningCount.set(0)
  }

  private recount(): void {
    let count = 0
    for (const status of this.statusMap.values()) {
      if (status === 'running') count++
    }
    this._runningCount.set(count)
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
}
