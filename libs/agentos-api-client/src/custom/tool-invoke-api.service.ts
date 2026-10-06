import { HttpClient } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'

/**
 * Request body for POST /api/tools/invoke.
 */
export interface ToolInvokeRequest {
  namespaceId: string
  userId?: string
  toolName: string
  /** Raw JSON string passed to the tool's executeWithJson. Omit for tools with no input. */
  payload?: string
}

/**
 * Response body from POST /api/tools/invoke.
 */
export interface ToolInvokeResponse {
  toolName: string
  output: string
  success: boolean
  metadata: Record<string, unknown>
  /**
   * Machine-readable structured output conforming to the tool's declared outputSchema.
   * Null for text-only tools.
   */
  structuredOutput?: unknown
  errorType?: string
  errorMessage?: string
}

/**
 * ToolInvokeApiService — hand-written client for the tool-invoke debug endpoint.
 *
 * POST /api/tools/invoke is SUPER_ADMIN-only and is not in the generated OpenAPI spec
 * (it is a debug/test utility). This service wraps it manually, following the same
 * pattern as IntegrationConfigExportService.
 */
@Injectable({ providedIn: 'root' })
export class ToolInvokeApiService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  /**
   * Resolve `request.toolName` from the effective integration configs for the given
   * namespace (and optional user) and execute it with `request.payload`.
   */
  invoke(request: ToolInvokeRequest): Observable<ToolInvokeResponse> {
    const url = `${this.config.basePath}/api/tools/invoke`
    return this.http.post<ToolInvokeResponse>(url, request)
  }
}
