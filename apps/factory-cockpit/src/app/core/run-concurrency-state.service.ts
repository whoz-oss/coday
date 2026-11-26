import { Injectable, inject, signal } from '@angular/core'
import { Observable, catchError, finalize, map, tap, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, RunConcurrencySettings } from './factory-api.service'

/** Page-scoped coordination for the maximum number of parallel workflow runs. */
@Injectable()
export class RunConcurrencyStateService {
  private readonly api = inject(FactoryApiService)

  readonly settings = signal<RunConcurrencySettings | null>(null)
  readonly loading = signal(false)
  readonly saving = signal(false)
  readonly error = signal<FactoryApiError | null>(null)

  load(): void {
    this.loading.set(true)
    this.error.set(null)
    this.api
      .getRunConcurrency()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (settings) => this.settings.set(settings),
        error: (error: FactoryApiError) => this.error.set(error),
      })
  }

  /** Persists the new limit; the returned observable errors with the {@link FactoryApiError} after setting {@link error}. */
  save(maxParallelRuns: number): Observable<void> {
    this.saving.set(true)
    this.error.set(null)
    return this.api.updateRunConcurrency(maxParallelRuns).pipe(
      tap((settings) => this.settings.set(settings)),
      map(() => undefined),
      catchError((error: FactoryApiError) => {
        this.error.set(error)
        return throwError(() => error)
      }),
      finalize(() => this.saving.set(false))
    )
  }
}
