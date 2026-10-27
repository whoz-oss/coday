import { Injectable, inject, signal } from '@angular/core'
import { finalize } from 'rxjs'
import { FactoryApiError, FactoryApiService, NamespaceOption, extractNamespaceOptions } from './factory-api.service'

/** Page-scoped supplementary state for run history. */
@Injectable()
export class HistoryStateService {
  private readonly api = inject(FactoryApiService)

  readonly namespaces = signal<NamespaceOption[]>([])
  readonly loading = signal(false)
  readonly error = signal<FactoryApiError | null>(null)

  loadNamespaces(): void {
    this.loading.set(true)
    this.error.set(null)
    this.api
      .getNamespaces()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (payload) => this.namespaces.set(extractNamespaceOptions(payload)),
        error: (error: FactoryApiError) => {
          this.namespaces.set([])
          this.error.set(error)
        },
      })
  }
}
