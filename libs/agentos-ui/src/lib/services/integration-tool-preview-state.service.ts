import { HttpErrorResponse } from '@angular/common/http'
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { IntegrationConfigToolPreview } from '@whoz-oss/agentos-api-client'
import { IntegrationConfigStateService } from './integration-config-state.service'

export type IntegrationToolPreviewStatus = 'idle' | 'loading' | 'success' | 'error'

/**
 * Human-readable reason for a failed preview call. Spring error bodies carry `message`
 * (400 / 422 functional exceptions); the access-denied handler writes `error` instead.
 */
export function describePreviewError(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { message?: unknown; error?: unknown } | null
    const fromBody = [body?.message, body?.error].find((v): v is string => typeof v === 'string' && v.length > 0)
    return fromBody ?? `Tool preview failed (HTTP ${err.status})`
  }
  if (err instanceof Error && err.message) {
    return `Tool preview failed: ${err.message}`
  }
  return 'Tool preview failed'
}

/**
 * IntegrationToolPreviewStateService — state of the "Preview tools" action of one integration
 * form (idle -> loading -> success | error). Provided at the component level so each form
 * owns its own preview and the subscription follows the component lifecycle.
 *
 * The HTTP call goes through `IntegrationConfigStateService.previewTools`; the optional
 * namespace is the route namespace of the form (required by the backend for platform and
 * user-global rows, which carry none).
 */
@Injectable()
export class IntegrationToolPreviewStateService {
  private readonly integrationState = inject(IntegrationConfigStateService)
  private readonly destroyRef = inject(DestroyRef)

  private readonly statusState = signal<IntegrationToolPreviewStatus>('idle')
  private readonly previewState = signal<IntegrationConfigToolPreview | null>(null)
  private readonly errorMessageState = signal<string | null>(null)

  readonly status = this.statusState.asReadonly()
  readonly preview = this.previewState.asReadonly()
  readonly errorMessage = this.errorMessageState.asReadonly()
  readonly isLoading = computed(() => this.statusState() === 'loading')

  load(configId: string, namespaceId: string | null): void {
    if (this.isLoading()) return
    this.statusState.set('loading')
    this.previewState.set(null)
    this.errorMessageState.set(null)
    this.integrationState
      .previewTools(configId, namespaceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => {
          this.previewState.set(preview)
          this.statusState.set('success')
        },
        error: (err: unknown) => {
          console.error('[IntegrationToolPreview] Preview failed', err)
          this.errorMessageState.set(describePreviewError(err))
          this.statusState.set('error')
        },
      })
  }
}
