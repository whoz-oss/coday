import { inject, Injectable } from '@angular/core'
import { HttpClient, HttpHeaders } from '@angular/common/http'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { ScheduledPrompt } from './scheduled-prompt.model'

/** Request body for POST /api/scheduled-prompts/search */
export interface ScheduledPromptSearchRequest {
  namespaceId?: string | null
  userId?: string | null
  agentConfigIds?: string[]
}

/** Request body for POST /api/scheduled-prompts/effective */
export interface ScheduledPromptEffectiveRequest {
  namespaceId: string
  userId: string
  agentConfigId?: string
}

/**
 * ScheduledPromptApiService — hand-written HTTP client for the Agentic Scheduler endpoints.
 *
 * Lives in src/custom/ to survive OpenAPI client regeneration.
 *
 * Backend endpoints (all at /api/scheduled-prompts):
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
export class ScheduledPromptApiService {
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

  getById(id: string): Observable<ScheduledPrompt> {
    return this.http.get<ScheduledPrompt>(`${this.basePath}/api/scheduled-prompts/${id}`, {
      headers: this.acceptHeaders,
    })
  }

  getByIds(ids: string[], withRemoved = false): Observable<ScheduledPrompt[]> {
    return this.http.post<ScheduledPrompt[]>(
      `${this.basePath}/api/scheduled-prompts/by-ids`,
      { ids, withRemoved },
      { headers: this.jsonHeaders }
    )
  }

  /**
   * POST /api/scheduled-prompts/search
   * Returns scheduled prompts at an exact scope level (no merge, no inheritance).
   */
  search(request: ScheduledPromptSearchRequest): Observable<ScheduledPrompt[]> {
    return this.http.post<ScheduledPrompt[]>(`${this.basePath}/api/scheduled-prompts/search`, request, {
      headers: this.jsonHeaders,
    })
  }

  /**
   * POST /api/scheduled-prompts/effective
   * Returns the merged (effective) set for a user in a namespace context.
   */
  effective(request: ScheduledPromptEffectiveRequest): Observable<ScheduledPrompt[]> {
    return this.http.post<ScheduledPrompt[]>(`${this.basePath}/api/scheduled-prompts/effective`, request, {
      headers: this.jsonHeaders,
    })
  }

  create(payload: ScheduledPrompt): Observable<ScheduledPrompt> {
    return this.http.post<ScheduledPrompt>(`${this.basePath}/api/scheduled-prompts`, payload, {
      headers: this.jsonHeaders,
    })
  }

  update(id: string, payload: ScheduledPrompt): Observable<ScheduledPrompt> {
    return this.http.put<ScheduledPrompt>(`${this.basePath}/api/scheduled-prompts/${id}`, payload, {
      headers: this.jsonHeaders,
    })
  }

  toggle(id: string): Observable<ScheduledPrompt> {
    return this.http.patch<ScheduledPrompt>(`${this.basePath}/api/scheduled-prompts/${id}/toggle`, null, {
      headers: this.acceptHeaders,
    })
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.basePath}/api/scheduled-prompts/${id}`)
  }
}
