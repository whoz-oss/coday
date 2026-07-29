import { inject, Injectable } from '@angular/core'
import {
  ScheduledPrompt,
  ScheduledPromptControllerService,
  ScheduledPromptEffectiveRequest,
  ScheduledPromptSearchRequest,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * ScheduledPromptStateService — API layer facade for ScheduledPrompt entities.
 *
 * Follows the two-layer pattern: components never inject ScheduledPromptControllerService
 * directly. All HTTP calls go through this service.
 *
 * This service is intentionally thin — it delegates 1:1 to the generated controller service.
 * State management (refresh$, caching) lives in the consuming components.
 *
 * ## Scope model
 *
 * | namespaceId | userId | Scope          | Priority |
 * |-------------|--------|----------------|----------|
 * | null        | null   | Platform       | 0 (lowest) |
 * | null        | set    | User-global    | 1 |
 * | set         | null   | Namespace      | 2 |
 * | set         | set    | User×Namespace | 3 (highest) |
 */
@Injectable({ providedIn: 'root' })
export class ScheduledPromptStateService {
  private readonly api = inject(ScheduledPromptControllerService)

  getById(id: string): Observable<ScheduledPrompt> {
    return this.api.getByIdScheduledPrompt(id)
  }

  /** Platform-level scheduled prompts (namespaceId = null, userId = null). */
  listPlatform(): Observable<ScheduledPrompt[]> {
    return this.api.searchScheduledPrompt({ namespaceId: null, userId: null })
  }

  /** Namespace-shared scheduled prompts (namespaceId = nsId, userId = null). */
  listByNamespace(namespaceId: string): Observable<ScheduledPrompt[]> {
    return this.api.searchScheduledPrompt({ namespaceId, userId: null })
  }

  /** Generic exact-scope search. */
  search(request: ScheduledPromptSearchRequest): Observable<ScheduledPrompt[]> {
    return this.api.searchScheduledPrompt(request)
  }

  /**
   * Returns the effective (merged) scheduled prompt list for a namespace+user context.
   * Merges platform, namespace-shared, user-global and user×namespace layers by name.
   */
  listEffective(request: ScheduledPromptEffectiveRequest): Observable<ScheduledPrompt[]> {
    return this.api.effectiveScheduledPrompt(request)
  }

  create(payload: ScheduledPrompt): Observable<ScheduledPrompt> {
    return this.api.createScheduledPrompt(payload)
  }

  update(id: string, payload: ScheduledPrompt): Observable<ScheduledPrompt> {
    return this.api.updateScheduledPrompt(id, payload)
  }

  /** Enables a scheduled prompt. Idempotent — no-op if already enabled. */
  enable(id: string): Observable<ScheduledPrompt> {
    return this.api.enableScheduledPrompt(id)
  }

  /** Disables a scheduled prompt. Idempotent — no-op if already disabled. */
  disable(id: string): Observable<ScheduledPrompt> {
    return this.api.disableScheduledPrompt(id)
  }

  delete(id: string): Observable<unknown> {
    return this.api.deleteScheduledPrompt(id)
  }
}
