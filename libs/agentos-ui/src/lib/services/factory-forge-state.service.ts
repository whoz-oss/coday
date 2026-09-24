import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core'
import { interval, Subscription } from 'rxjs'
import { FactoryApiService, FactoryForgeRun } from './factory-api.service'

const POLL_INTERVAL_MS = 10_000

/** Read-only state for independently persisted Forge Epic/Story JSONL ledgers. */
@Injectable({ providedIn: 'root' })
export class FactoryForgeStateService implements OnDestroy {
  private readonly api = inject(FactoryApiService)

  readonly runs = signal<FactoryForgeRun[]>([])
  readonly loading = signal(false)
  readonly error = signal<string | null>(null)
  readonly hasActiveRuns = computed(() =>
    this.runs().some((run) => run.status === 'in_progress' || run.status === 'waiting_human')
  )

  private pollingSubscription: Subscription | null = null
  private currentPollingNamespaceId: string | null = null

  load(namespaceId: string): void {
    this.loading.set(true)
    this.error.set(null)
    this.api.listForgeRuns(namespaceId).subscribe({
      next: (runs) => {
        this.runs.set(runs)
        this.loading.set(false)
      },
      error: () => {
        this.error.set('Forge Epic runs could not be loaded. Please try again.')
        this.loading.set(false)
      },
    })
  }

  startPolling(namespaceId: string): void {
    if (this.pollingSubscription && this.currentPollingNamespaceId === namespaceId) return
    this.stopPolling()
    this.currentPollingNamespaceId = namespaceId
    this.pollingSubscription = interval(POLL_INTERVAL_MS).subscribe(() => {
      if (this.hasActiveRuns()) this.load(namespaceId)
    })
  }

  stopPolling(): void {
    this.pollingSubscription?.unsubscribe()
    this.pollingSubscription = null
    this.currentPollingNamespaceId = null
  }

  ngOnDestroy(): void {
    this.stopPolling()
  }
}
