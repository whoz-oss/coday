import { inject, Injectable } from '@angular/core'
import {
  ScheduledPrompt,
  ScheduledPromptApiService,
  ScheduledPromptEffectiveRequest,
  ScheduledPromptSearchRequest,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * ScheduledPromptStateService — API layer facade for ScheduledPrompt entities.
 *
 * Follows the two-layer pattern: components never inject ScheduledPromptApiService
 * directly. All HTTP calls go through this service.
 *
 * This service is intentionally thin — it delegates 1:1 to the API service.
 * State management (refresh$, caching) lives in the consuming components,
 * consistent with the PromptStateService pattern.
 *
 * ## Scope model
 *
 * | namespaceId | userId | Scope          | Priority |
 * |-------------|--------|----------------|----------|
 * | null        | null   | Platform       | 0 (lowest) |
 * | null        | set    | User-global    | 1 |
 * | set         | null   | Namespace      | 2 |
 * | set         | set    | User×Namespace | 3 (highest) |
 *
 * ## Endpoint mapping
 *
 * | Method          | Endpoint                          | Description                    |
 * |-----------------|-----------------------------------|--------------------------------|
 * | getById         | GET  /{id}                        | Fetch single scheduled prompt  |
 * | listPlatform    | POST /search {null, null}         | Platform-level prompts         |
 * | listByNamespace | POST /search {nsId, null}         | Namespace-level prompts        |
 * | search          | POST /search                      | Exact-scope search             |
 * | listEffective   | POST /effective                   | Merged set for user+namespace  |
 * | create          | POST /                            | Create a scheduled prompt      |
 * | update          | PUT  /{id}                        | Update a scheduled prompt      |
 * | toggle          | PATCH /{id}/toggle                | Flip enabled flag              |
 * | delete          | DELETE /{id}                      | Delete a scheduled prompt      |
 */
@Injectable({ providedIn: 'root' })
export class ScheduledPromptStateService {
  private readonly api = inject(ScheduledPromptApiService)

  getById(id: string): Observable<ScheduledPrompt> {
    return this.api.getById(id)
  }

  /** Platform-level scheduled prompts (namespaceId = null, userId = null). */
  listPlatform(): Observable<ScheduledPrompt[]> {
    return this.api.search({ namespaceId: null, userId: null })
  }

  /** Namespace-shared scheduled prompts (namespaceId = nsId, userId = null). */
  listByNamespace(namespaceId: string): Observable<ScheduledPrompt[]> {
    return this.api.search({ namespaceId, userId: null })
  }

  /** Generic exact-scope search. */
  search(request: ScheduledPromptSearchRequest): Observable<ScheduledPrompt[]> {
    return this.api.search(request)
  }

  /**
   * Returns the effective (merged) scheduled prompt list for a namespace+user context.
   * Merges platform, namespace-shared, user-global and user×namespace layers by name.
   */
  listEffective(request: ScheduledPromptEffectiveRequest): Observable<ScheduledPrompt[]> {
    return this.api.effective(request)
  }

  create(payload: ScheduledPrompt): Observable<ScheduledPrompt> {
    return this.api.create(payload)
  }

  update(id: string, payload: ScheduledPrompt): Observable<ScheduledPrompt> {
    return this.api.update(id, payload)
  }

  /**
   * Flips the enabled flag on a single scheduled prompt.
   */
  toggle(id: string): Observable<ScheduledPrompt> {
    return this.api.toggle(id)
  }

  delete(id: string): Observable<unknown> {
    return this.api.delete(id)
  }
}
