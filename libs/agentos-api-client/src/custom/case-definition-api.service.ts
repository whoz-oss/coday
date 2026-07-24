import { inject, Injectable } from '@angular/core'
import { HttpClient, HttpHeaders } from '@angular/common/http'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { CaseDefinition } from './case-definition.model'

/** Request body for POST /api/case-definitions/search */
export interface CaseDefinitionSearchRequest {
  namespaceId?: string | null
  userId?: string | null
  agentConfigIds?: string[]
}

/** Request body for POST /api/case-definitions/effective */
export interface CaseDefinitionEffectiveRequest {
  namespaceId: string
  userId: string
  agentConfigId?: string
}

/**
 * CaseDefinitionApiService — hand-written HTTP client for the Agentic Scheduler endpoints.
 *
 * Lives in src/custom/ to survive OpenAPI client regeneration.
 *
 * Backend endpoints (all at /api/case-definitions):
 *   GET    /{id}          → getById
 *   POST   /by-ids        → getByIds
 *   POST   /search        → search
 *   POST   /effective     → effective
 *   POST   /              → create
 *   PUT    /{id}          → update
 *   PATCH  /{id}/toggle   → toggle
 *   DELETE /{id}          → delete
 */
@Injectable({ providedIn: 'root' })
export class CaseDefinitionApiService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  private get basePath(): string {
    return this.config.basePath ?? ''
  }

  private get jsonHeaders(): HttpHeaders {
    return new HttpHeaders({ 'Content-Type': 'application/json', Accept: 'application/json' })
  }

  private get acceptHeaders(): HttpHeaders {
    return new HttpHeaders({ Accept: 'application/json' })
  }

  getById(id: string): Observable<CaseDefinition> {
    return this.http.get<CaseDefinition>(`${this.basePath}/api/case-definitions/${id}`, {
      headers: this.acceptHeaders,
    })
  }

  getByIds(ids: string[], withRemoved = false): Observable<CaseDefinition[]> {
    return this.http.post<CaseDefinition[]>(
      `${this.basePath}/api/case-definitions/by-ids`,
      { ids, withRemoved },
      { headers: this.jsonHeaders }
    )
  }

  /**
   * POST /api/case-definitions/search
   * Returns case definitions at an exact scope level (no merge, no inheritance).
   *
   * scope = (namespaceId?, userId?) combination:
   *   (null, null) → platform
   *   (ns,   null) → namespace-shared
   *   (null, user) → user-global
   *   (ns,   user) → user × namespace
   */
  search(request: CaseDefinitionSearchRequest): Observable<CaseDefinition[]> {
    return this.http.post<CaseDefinition[]>(`${this.basePath}/api/case-definitions/search`, request, {
      headers: this.jsonHeaders,
    })
  }

  /**
   * POST /api/case-definitions/effective
   * Returns the merged (effective) set for a user in a namespace context.
   */
  effective(request: CaseDefinitionEffectiveRequest): Observable<CaseDefinition[]> {
    return this.http.post<CaseDefinition[]>(`${this.basePath}/api/case-definitions/effective`, request, {
      headers: this.jsonHeaders,
    })
  }

  create(payload: CaseDefinition): Observable<CaseDefinition> {
    return this.http.post<CaseDefinition>(`${this.basePath}/api/case-definitions`, payload, {
      headers: this.jsonHeaders,
    })
  }

  update(id: string, payload: CaseDefinition): Observable<CaseDefinition> {
    return this.http.put<CaseDefinition>(`${this.basePath}/api/case-definitions/${id}`, payload, {
      headers: this.jsonHeaders,
    })
  }

  toggle(id: string): Observable<CaseDefinition> {
    return this.http.patch<CaseDefinition>(`${this.basePath}/api/case-definitions/${id}/toggle`, null, {
      headers: this.acceptHeaders,
    })
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.basePath}/api/case-definitions/${id}`)
  }
}
