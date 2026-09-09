import { HttpClient } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'

/**
 * PromptExportService — hand-written wrapper for the YAML export endpoint.
 *
 * `GET /api/prompts/{id}/export` returns `application/yaml` exclusively.
 * The generated `PromptControllerService.exportPrompt()` cannot
 * be used directly because the OpenAPI generator's `selectHeaderAccept` prefers JSON
 * when both MIME types are listed, causing a 406. Additionally, `application/yaml` is
 * not treated as text by the generator's responseType logic, so it would return a Blob
 * instead of the raw YAML string.
 *
 * This service bypasses both issues by calling HttpClient directly with
 * `Accept: application/yaml` and `responseType: 'text'`.
 */
@Injectable({ providedIn: 'root' })
export class PromptExportService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  /**
   * Fetch a single prompt as a YAML string.
   * The returned string is the raw YAML, ready to be saved as a `.yaml` file.
   */
  exportAsYaml(id: string): Observable<string> {
    const url = `${this.config.basePath}/api/prompts/${encodeURIComponent(id)}/export`
    return this.http.get(url, {
      headers: { Accept: 'application/yaml' },
      responseType: 'text',
    })
  }
}
