import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'

/**
 * AiModelAliasService — hand-written client for the AI model aliases endpoint.
 *
 * The endpoint `GET /api/ai-models/aliases-by-namespaceId/{namespaceId}` is not yet
 * captured in the generated OpenAPI client. This service lives in `src/custom/` so
 * it is safe from client regeneration.
 *
 * Returns the list of distinct alias strings configured for AI models in a namespace.
 * An empty array means no aliases are configured — the namespace will use its default.
 *
 * Usage:
 *   const aliases$ = this.aiModelAlias.listAliasesByNamespace(namespaceId)
 *   aliases$.subscribe(aliases => { ... })
 */
@Injectable({ providedIn: 'root' })
export class AiModelAliasService {
  private readonly config = inject(Configuration)
  private readonly http = inject(HttpClient)

  /**
   * Fetch the list of distinct AI model alias strings for a namespace.
   *
   * Endpoint: GET /api/ai-models/aliases-by-namespaceId/{namespaceId}
   *
   * Returns an empty array when the namespace has no aliases configured,
   * or when the endpoint is not yet available (via catchError at call-site).
   */
  listAliasesByNamespace(namespaceId: string): Observable<string[]> {
    const url = `${this.config.basePath}/api/ai-models/aliases-by-namespaceId/${namespaceId}`
    return this.http.get<string[]>(url)
  }
}
