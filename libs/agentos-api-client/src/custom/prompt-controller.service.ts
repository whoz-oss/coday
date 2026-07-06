import { Inject, Injectable, Optional } from '@angular/core'
import { HttpClient, HttpContext } from '@angular/common/http'
import { Observable } from 'rxjs'
import { BASE_PATH } from '../lib/variables'
import { Configuration } from '../lib/configuration'
import { BaseService } from '../lib/api.base.service'

/**
 * A single ordered text command in a prompt.
 * May include @AgentName references and {{paramName}} interpolations.
 */
export interface PromptParameter {
  name: string
  description?: string
  defaultValue: string
}

/**
 * Prompt resource shape as defined by the AgentOS backend.
 *
 * content: ordered list of text commands (each is a free-text string).
 * parameters: named placeholders used in content entries.
 */
export interface Prompt {
  id?: string
  namespaceId?: string
  userId?: string
  name: string
  description?: string
  content: string[]
  parameters: PromptParameter[]
}

/**
 * Hand-written API client for the Prompt resource.
 *
 * This service lives in src/custom/ so it is never overwritten by the OpenAPI
 * generator. Once the backend adds Prompt to its OpenAPI spec and the client is
 * regenerated, this file should be removed and imports updated to the generated
 * PromptControllerService.
 *
 * Endpoints:
 *   POST   /api/prompts                    → create
 *   GET    /api/prompts                    → list by scope (no params = platform)
 *   GET    /api/prompts/{id}               → getById
 *   PUT    /api/prompts/{id}               → update
 *   DELETE /api/prompts/{id}               → delete (soft)
 *   GET    /api/prompts/by-parentId/<uuid>   → list by namespace
 */
@Injectable({
  providedIn: 'root',
})
export class PromptControllerService extends BaseService {
  constructor(
    protected httpClient: HttpClient,
    @Optional() @Inject(BASE_PATH) basePath: string | string[],
    @Optional() configuration?: Configuration
  ) {
    super(basePath, configuration)
  }

  /** List platform-level prompts (namespaceId null, userId null). */
  listPlatformPrompt(): Observable<Prompt[]> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.get<Prompt[]>(`${basePath}/api/prompts`, {
      headers: this.defaultHeaders,
      params: { scope: 'PLATFORM' },
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }

  /** List namespace-shared prompts (userId null) for a given namespace. */
  listByNamespacePrompt(namespaceId: string): Observable<Prompt[]> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.get<Prompt[]>(`${basePath}/api/prompts`, {
      headers: this.defaultHeaders,
      params: { scope: 'NAMESPACE', namespaceId },
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }

  /** Retrieve a single prompt by its UUID. */
  getByIdPrompt(id: string): Observable<Prompt> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.get<Prompt>(`${basePath}/api/prompts/${encodeURIComponent(id)}`, {
      headers: this.defaultHeaders,
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }

  /** Create a new prompt. */
  createPrompt(prompt: Prompt): Observable<Prompt> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.post<Prompt>(`${basePath}/api/prompts`, prompt, {
      headers: this.defaultHeaders.set('Content-Type', 'application/json'),
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }

  /** Update an existing prompt. */
  updatePrompt(id: string, prompt: Prompt): Observable<Prompt> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.put<Prompt>(`${basePath}/api/prompts/${encodeURIComponent(id)}`, prompt, {
      headers: this.defaultHeaders.set('Content-Type', 'application/json'),
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }

  /**
   * Returns the resolved set of prompts for the given namespace+user context.
   * Merges platform, namespace-shared, user-global and user×namespace layers by name.
   * Used for slash-command autocomplete in the chat composer.
   */
  listEffectivePrompts(namespaceId: string): Observable<Prompt[]> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.get<Prompt[]>(`${basePath}/api/prompts/effective`, {
      headers: this.defaultHeaders,
      params: { namespaceId },
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }

  /** Soft-delete a prompt. */
  deletePrompt(id: string): Observable<void> {
    const { basePath, withCredentials } = this.configuration
    return this.httpClient.delete<void>(`${basePath}/api/prompts/${encodeURIComponent(id)}`, {
      headers: this.defaultHeaders,
      ...(withCredentials ? { withCredentials } : {}),
      context: new HttpContext(),
    })
  }
}
