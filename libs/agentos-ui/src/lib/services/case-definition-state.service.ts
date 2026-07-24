import { inject, Injectable } from '@angular/core'
import {
  CaseDefinition,
  CaseDefinitionApiService,
  CaseDefinitionEffectiveRequest,
  CaseDefinitionSearchRequest,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * CaseDefinitionStateService — API layer facade for CaseDefinition entities.
 *
 * Follows the two-layer pattern: components never inject CaseDefinitionApiService
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
 * | Method       | Endpoint                          | Description                    |
 * |--------------|-----------------------------------|--------------------------------|
 * | getById      | GET  /{id}                        | Fetch single definition        |
 * | listPlatform | POST /search {null, null}         | Platform-level definitions     |
 * | listByNs     | POST /search {nsId, null}         | Namespace-level definitions    |
 * | search       | POST /search                      | Exact-scope search             |
 * | listEffective| POST /effective                   | Merged set for user+namespace  |
 * | create       | POST /                            | Create a definition            |
 * | update       | PUT  /{id}                        | Update a definition            |
 * | toggle       | PATCH /{id}/toggle                | Flip enabled flag              |
 * | delete       | DELETE /{id}                      | Delete a definition            |
 */
@Injectable({ providedIn: 'root' })
export class CaseDefinitionStateService {
  private readonly api = inject(CaseDefinitionApiService)

  getById(id: string): Observable<CaseDefinition> {
    return this.api.getById(id)
  }

  /** Platform-level case definitions (namespaceId = null, userId = null). */
  listPlatform(): Observable<CaseDefinition[]> {
    return this.api.search({ namespaceId: null, userId: null })
  }

  /** Namespace-shared case definitions (namespaceId = nsId, userId = null). */
  listByNamespace(namespaceId: string): Observable<CaseDefinition[]> {
    return this.api.search({ namespaceId, userId: null })
  }

  /** Generic exact-scope search. */
  search(request: CaseDefinitionSearchRequest): Observable<CaseDefinition[]> {
    return this.api.search(request)
  }

  /**
   * Returns the effective (merged) case definition list for a namespace+user context.
   * Merges platform, namespace-shared, user-global and user×namespace layers by name.
   */
  listEffective(request: CaseDefinitionEffectiveRequest): Observable<CaseDefinition[]> {
    return this.api.effective(request)
  }

  create(payload: CaseDefinition): Observable<CaseDefinition> {
    return this.api.create(payload)
  }

  update(id: string, payload: CaseDefinition): Observable<CaseDefinition> {
    return this.api.update(id, payload)
  }

  /**
   * Flips the enabled flag on a single case definition.
   * Specific to CaseDefinition — Prompt has no equivalent.
   */
  toggle(id: string): Observable<CaseDefinition> {
    return this.api.toggle(id)
  }

  delete(id: string): Observable<unknown> {
    return this.api.delete(id)
  }
}
