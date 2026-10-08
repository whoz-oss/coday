import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http'
import { Injectable, inject } from '@angular/core'
import { Observable, throwError } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { generateCorrelationId } from './factory-api.service'

/**
 * Minimal DTO for AgentOS Case creation.
 *
 * Mirrors the subset of `CaseDto` used by `POST /api/cases`.
 * `namespaceId` is required and must be a valid UUID string — the AgentOS
 * controller validates it with `@NotNull UUID namespaceId`. If the Factory
 * namespace id is a slug rather than a UUID the request will be rejected with
 * HTTP 400; callers must verify the value before calling this service.
 */
export interface CreateCaseRequest {
  /** UUID of the namespace the case belongs to. Must be a valid UUID string. */
  namespaceId: string
  /** Human-readable title of the case. */
  title?: string
}

/** Subset of the AgentOS `CaseDto` response used by the cockpit. */
export interface CreatedCase {
  id: string
  namespaceId: string
  title: string
  status: string
}

/** Normalized error surfaced by {@link AgentOsApiService}. */
export interface AgentOsApiError {
  code: string
  message: string
  status: number
  raw: unknown
}

/**
 * Thin Angular HTTP client for the AgentOS case surface.
 *
 * Only the two operations needed by the "Demander au superviseur" flow are
 * exposed:
 *  1. `POST /api/cases` — create a new assistance case in a namespace.
 *  2. `POST /api/cases/{id}/messages` — send the initial message (with
 *     the `@Heimdall` mention so AgentOS routes the turn to that agent).
 *
 * Both calls go through the dev proxy (`/api/cases → localhost:8124`) and
 * the gateway in production. No authentication header is added here:
 * the gateway/proxy propagates the session cookie or auth header.
 */
@Injectable({ providedIn: 'root' })
export class AgentOsApiService {
  private readonly http = inject(HttpClient)

  /**
   * POST `/api/cases` — create a new AgentOS case in the given namespace.
   *
   * Returns the created case DTO (HTTP 201). Throws an {@link AgentOsApiError}
   * on any HTTP or transport failure.
   */
  createCase(request: CreateCaseRequest): Observable<CreatedCase> {
    const headers = new HttpHeaders()
      .set('Content-Type', 'application/json')
      .set('X-Correlation-Id', generateCorrelationId())
    return this.http
      .post<CreatedCase>('/api/cases', request, { headers })
      .pipe(catchError((error: unknown) => throwError(() => normalizeAgentOsError(error))))
  }

  /**
   * POST `/api/cases/{caseId}/messages` — append a user message to the case.
   *
   * Used immediately after {@link createCase} to send the supervisor context
   * message (which includes the `@Heimdall` mention so AgentOS selects the
   * right agent for this case).
   *
   * Returns `void` (HTTP 200, no body). Throws an {@link AgentOsApiError}
   * on any HTTP or transport failure.
   */
  addMessage(caseId: string, content: string): Observable<void> {
    const headers = new HttpHeaders()
      .set('Content-Type', 'application/json')
      .set('X-Correlation-Id', generateCorrelationId())
    return this.http
      .post<void>(`/api/cases/${encodeURIComponent(caseId)}/messages`, { content }, { headers })
      .pipe(catchError((error: unknown) => throwError(() => normalizeAgentOsError(error))))
  }
}

function normalizeAgentOsError(error: unknown): AgentOsApiError {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as {
      error?: { code?: unknown; message?: unknown }
      code?: unknown
      message?: unknown
    } | null
    const envelope = body?.error
    const code =
      typeof envelope?.code === 'string'
        ? envelope.code
        : typeof body?.code === 'string'
          ? body.code
          : `HTTP_${error.status}`
    const message =
      typeof envelope?.message === 'string'
        ? envelope.message
        : typeof body?.message === 'string'
          ? body.message
          : error.message || 'AgentOS API request failed'
    return { code, message, status: error.status, raw: error }
  }
  if (error instanceof Error) {
    return { code: 'UNKNOWN_ERROR', message: error.message, status: 0, raw: error }
  }
  return { code: 'UNKNOWN_ERROR', message: 'AgentOS API request failed', status: 0, raw: error }
}
