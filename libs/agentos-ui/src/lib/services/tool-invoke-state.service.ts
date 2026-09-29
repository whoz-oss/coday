import { inject, Injectable, signal } from '@angular/core'
import { ToolInvokeApiService, ToolInvokeResponse } from '@whoz-oss/agentos-api-client'
import { HttpErrorResponse } from '@angular/common/http'
import { Observable, tap } from 'rxjs'

/**
 * ToolInvokeStateService — manages state for the tool-invoke debug screen.
 *
 * Follows the two-layer service pattern: this service owns reactive state and
 * coordinates calls to ToolInvokeApiService (API layer). The component injects
 * only this service, never the API service directly.
 */
@Injectable({ providedIn: 'root' })
export class ToolInvokeStateService {
  private readonly api = inject(ToolInvokeApiService)

  readonly isLoading = signal(false)
  readonly result = signal<ToolInvokeResponse | null>(null)
  readonly errorMessage = signal<string | null>(null)

  /**
   * Execute the named tool with the given payload in the given namespace.
   * Updates isLoading / result / errorMessage signals accordingly.
   */
  invoke(
    namespaceId: string,
    userId: string | undefined,
    toolName: string,
    payloadJson: string | undefined
  ): Observable<ToolInvokeResponse> {
    this.isLoading.set(true)
    this.result.set(null)
    this.errorMessage.set(null)

    return this.api
      .invoke({
        namespaceId,
        userId: userId || undefined,
        toolName,
        payload: payloadJson || undefined,
      })
      .pipe(
        tap({
          next: (res) => {
            this.result.set(res)
            this.isLoading.set(false)
          },
          error: (err: unknown) => {
            this.errorMessage.set(extractErrorMessage(err))
            this.isLoading.set(false)
          },
        })
      )
  }

  reset(): void {
    this.result.set(null)
    this.errorMessage.set(null)
  }
}

/**
 * Extract a human-readable error message from an unknown HTTP error.
 *
 * Angular's HttpClient surfaces errors as `HttpErrorResponse`. The `.message`
 * property on `HttpErrorResponse` is the generic transport-level string
 * ("Http failure response for ..."), not the backend body. The actual backend
 * error detail lives in `.error`, which is the parsed JSON body for JSON
 * responses (e.g. a Spring `ProblemDetail` or a plain `{ message: string }`).
 *
 * Precedence:
 *   1. `HttpErrorResponse.error.message` — backend JSON body message field
 *   2. `HttpErrorResponse.error` as a string — plain-text backend body
 *   3. `HttpErrorResponse.statusText` — HTTP status phrase (last resort)
 *   4. `Error.message` — non-HTTP JS errors
 *   5. `'Unknown error'` — catch-all
 */
function extractErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error
    if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
      return body.message
    }
    if (typeof body === 'string' && body.trim().length > 0) {
      return body
    }
    return err.statusText || `HTTP ${err.status}`
  }
  if (err instanceof Error) {
    return err.message
  }
  return 'Unknown error'
}
