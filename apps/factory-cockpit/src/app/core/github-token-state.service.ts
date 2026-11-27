import { Injectable, inject, signal } from '@angular/core'
import { Observable, catchError, finalize, tap, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, FactoryGitHubSettings } from './factory-api.service'

/** Page-scoped coordination for the GitHub token used by the PR checks step. */
@Injectable()
export class GitHubTokenStateService {
  private readonly api = inject(FactoryApiService)

  readonly settings = signal<FactoryGitHubSettings | null>(null)
  readonly loading = signal(false)
  readonly saving = signal(false)
  readonly error = signal<FactoryApiError | null>(null)

  load(): void {
    this.loading.set(true)
    this.error.set(null)
    this.api
      .getGitHubToken()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (settings) => this.settings.set(settings),
        error: (error: FactoryApiError) => this.error.set(error),
      })
  }

  /**
   * Verifies and saves the token. A token GitHub rejects is not saved: the answer then has `status: 'invalid'`
   * and replaces the displayed status, the stored token being unchanged.
   */
  save(token: string): Observable<FactoryGitHubSettings> {
    return this.run(this.api.updateGitHubToken(token))
  }

  /** Removes the Admin token. */
  clear(): Observable<FactoryGitHubSettings> {
    return this.run(this.api.clearGitHubToken())
  }

  /** Re-tests the effective token against GitHub. */
  test(): Observable<FactoryGitHubSettings> {
    return this.run(this.api.testGitHubToken())
  }

  /** Runs a command: the returned observable errors with the {@link FactoryApiError} after setting {@link error}. */
  private run(call: Observable<FactoryGitHubSettings>): Observable<FactoryGitHubSettings> {
    this.saving.set(true)
    this.error.set(null)
    return call.pipe(
      tap((settings) => this.settings.set(settings)),
      catchError((error: FactoryApiError) => {
        this.error.set(error)
        return throwError(() => error)
      }),
      finalize(() => this.saving.set(false))
    )
  }
}
