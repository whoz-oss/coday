import { HttpClient, HttpParams } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'
import { ApplyRequest, BrowseResult, SetupStatus } from './setup.models'

/**
 * Pure HTTP layer of the first-run setup API (`/api/setup/*`). 1:1 with the Express routes, no logic,
 * no state: components go through SetupStateService.
 *
 * NOT routed through the AgentOS base path: these endpoints live on the Coday server itself and
 * answer before AgentOS is up.
 */
@Injectable({ providedIn: 'root' })
export class SetupApiService {
  private readonly http = inject(HttpClient)
  private readonly base = '/api/setup'

  getStatus(): Observable<SetupStatus> {
    return this.http.get<SetupStatus>(`${this.base}/status`)
  }

  saveIdentity(email: string): Observable<{ identity: { email: string } }> {
    return this.http.post<{ identity: { email: string } }>(`${this.base}/identity`, { email })
  }

  browse(path?: string, showHidden = false): Observable<BrowseResult> {
    let params = new HttpParams()
    if (path) params = params.set('path', path)
    if (showHidden) params = params.set('hidden', 'true')
    return this.http.get<BrowseResult>(`${this.base}/browse`, { params })
  }

  mkdir(path: string, name: string): Observable<{ path: string }> {
    return this.http.post<{ path: string }>(`${this.base}/mkdir`, { path, name })
  }

  apply(request: ApplyRequest): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.base}/apply`, request)
  }
}
