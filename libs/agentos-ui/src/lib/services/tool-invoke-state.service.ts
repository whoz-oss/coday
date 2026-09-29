import { inject, Injectable, signal } from '@angular/core'
import { ToolInvokeApiService, ToolInvokeResponse } from '@whoz-oss/agentos-api-client'
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
            const msg =
              err instanceof Error
                ? err.message
                : typeof err === 'object' && err !== null && 'message' in err
                  ? String((err as { message: unknown }).message)
                  : 'Unknown error'
            this.errorMessage.set(msg)
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
