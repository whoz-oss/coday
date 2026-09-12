import { inject, Injectable, signal } from '@angular/core'
import { FactoryApiService, FactoryForgeRun } from './factory-api.service'

/** Read-only state for server-projected Forge Epic/Story ledgers scoped to a namespace. */
@Injectable({ providedIn: 'root' })
export class FactoryForgeStateService {
  private readonly api = inject(FactoryApiService)

  readonly runs = signal<FactoryForgeRun[]>([])
  readonly loading = signal(false)
  readonly error = signal<string | null>(null)

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
}
