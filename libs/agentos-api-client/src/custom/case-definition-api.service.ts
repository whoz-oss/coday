import { inject, Injectable } from '@angular/core'
import { HttpClient, HttpHeaders } from '@angular/common/http'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { CaseDefinition } from './case-definition.model'

/**
 * CaseDefinitionApiService — hand-written HTTP client for the Agentic Scheduler endpoints.
 *
 * Lives in src/custom/ to survive OpenAPI client regeneration.
 *
 * Endpoints:
 *   GET    /api/case-definitions?namespaceId=
 *   POST   /api/case-definitions?namespaceId=
 *   GET    /api/case-definitions/{id}?namespaceId=
 *   PUT    /api/case-definitions/{id}?namespaceId=
 *   PATCH  /api/case-definitions/{id}/toggle?namespaceId=
 *   DELETE /api/case-definitions/{id}?namespaceId=
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

  list(namespaceId: string): Observable<CaseDefinition[]> {
    return this.http.get<CaseDefinition[]>(`${this.basePath}/api/case-definitions`, {
      headers: new HttpHeaders({ Accept: 'application/json' }),
      params: { namespaceId },
    })
  }

  create(namespaceId: string, task: CaseDefinition): Observable<CaseDefinition> {
    return this.http.post<CaseDefinition>(`${this.basePath}/api/case-definitions`, task, {
      headers: this.jsonHeaders,
      params: { namespaceId },
    })
  }

  getById(namespaceId: string, id: string): Observable<CaseDefinition> {
    return this.http.get<CaseDefinition>(`${this.basePath}/api/case-definitions/${id}`, {
      headers: new HttpHeaders({ Accept: 'application/json' }),
      params: { namespaceId },
    })
  }

  update(namespaceId: string, id: string, task: CaseDefinition): Observable<CaseDefinition> {
    return this.http.put<CaseDefinition>(`${this.basePath}/api/case-definitions/${id}`, task, {
      headers: this.jsonHeaders,
      params: { namespaceId },
    })
  }

  toggle(namespaceId: string, id: string): Observable<CaseDefinition> {
    return this.http.patch<CaseDefinition>(`${this.basePath}/api/case-definitions/${id}/toggle`, null, {
      headers: new HttpHeaders({ Accept: 'application/json' }),
      params: { namespaceId },
    })
  }

  delete(namespaceId: string, id: string): Observable<void> {
    return this.http.delete<void>(`${this.basePath}/api/case-definitions/${id}`, {
      params: { namespaceId },
    })
  }
}
