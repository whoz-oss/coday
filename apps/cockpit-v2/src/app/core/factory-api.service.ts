import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http'
import { Injectable, inject } from '@angular/core'
import { Observable, catchError, map, throwError } from 'rxjs'

export type WorkflowState = 'active' | 'removed'

/** Normalized error surfaced by {@link FactoryApiService}. */
export interface FactoryApiError {
  code: string
  message: string
  status: number
  raw: unknown
}

/** Shape of the `GET /api/factory/workflows` payload (inside the `{ data }` envelope). */
export interface WorkflowListPayload {
  namespaceId?: string
  state?: string
  items?: unknown[]
  [key: string]: unknown
}

/**
 * Angular HTTP client for the Spring/Kotlin factory-service workflow surface.
 *
 * Every call:
 *  - targets `/api/factory/workflows/*` (relative, same-origin behind the gateway);
 *  - carries an `X-Correlation-Id` header (generated when the caller omits one);
 *  - carries the optional `namespaceId` query param + `X-Namespace-Id` header
 *    ONLY when a non-blank namespace is supplied;
 *  - unwraps the `{ data: … }` response envelope (or returns the raw JSON/array);
 *  - normalizes any transport/HTTP failure into a {@link FactoryApiError}.
 */
@Injectable({ providedIn: 'root' })
export class FactoryApiService {
  private readonly http = inject(HttpClient)

  /** GET `/api/factory/workflows?state=active|removed[&namespaceId=…]` → snapshot items. */
  getWorkflows(state: WorkflowState = 'active', namespaceId?: string): Observable<unknown[]> {
    return this.request<unknown>('/api/factory/workflows', { state }, namespaceId).pipe(
      map((payload) => {
        if (Array.isArray(payload)) return payload
        const items = (payload as WorkflowListPayload | null)?.items
        return Array.isArray(items) ? items : []
      })
    )
  }

  /** GET `/api/factory/workflows/:id[?namespaceId=…]`. */
  getWorkflow(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.request<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}`, {}, namespaceId)
  }

  /** GET `/api/factory/workflows/:id/timing[?namespaceId=…]`. */
  getTiming(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.request<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/timing`, {}, namespaceId)
  }

  /** GET `/api/factory/workflows/:id/evidence[?namespaceId=…]`. */
  getEvidence(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.request<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/evidence`, {}, namespaceId)
  }

  /** GET `/api/factory/workflows/:id/metrics[?namespaceId=…]`. */
  getMetrics(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.request<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/metrics`, {}, namespaceId)
  }

  /**
   * GET `/api/factory/workflows/:id/interactions?state=all|open[&namespaceId=…]`.
   * Unwraps the `{ data: … }` envelope and returns the raw interaction records as
   * an array (empty when the backend exposes none).
   */
  getInteractions(workflowId: string, namespaceId?: string, state: 'all' | 'open' = 'all'): Observable<unknown[]> {
    return this.request<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/interactions`,
      { state },
      namespaceId
    ).pipe(
      map((payload) => {
        if (Array.isArray(payload)) return payload
        const obj = payload as { items?: unknown; data?: unknown } | null
        const items = obj?.items
        if (Array.isArray(items)) return items
        const nested = obj?.data
        return Array.isArray(nested) ? nested : []
      })
    )
  }

  /**
   * GET `/api/factory/workflows/:id/attempts[?namespaceId=…]`.
   * Unwraps the `{ data: […] }` or `{ data: { items: […] } }` envelope and
   * returns the raw attempt records as an array (empty when none are exposed).
   */
  getAttempts(workflowId: string, namespaceId?: string): Observable<unknown[]> {
    return this.request<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/attempts`,
      {},
      namespaceId
    ).pipe(
      map((payload) => {
        if (Array.isArray(payload)) return payload
        const obj = payload as { items?: unknown; data?: unknown } | null
        const items = obj?.items
        if (Array.isArray(items)) return items
        const nested = obj?.data
        return Array.isArray(nested) ? nested : []
      })
    )
  }

  /**
   * Issue a GET request and unwrap the response. `correlationId` is optional so
   * a caller can thread an existing trace id; otherwise a fresh one is minted.
   */
  private request<T>(
    path: string,
    query: Record<string, string | undefined>,
    namespaceId?: string,
    correlationId?: string
  ): Observable<T> {
    let params = new HttpParams()
    for (const [key, value] of Object.entries(query)) {
      if (typeof value === 'string' && value.length > 0) params = params.set(key, value)
    }

    let headers = new HttpHeaders().set('X-Correlation-Id', correlationId ?? generateCorrelationId())
    const namespace = namespaceId?.trim()
    if (namespace) {
      headers = headers.set('X-Namespace-Id', namespace)
      params = params.set('namespaceId', namespace)
    }

    return this.http.get<unknown>(path, { params, headers }).pipe(
      map((payload) => (isEnvelope(payload) ? (payload.data as T) : (payload as T))),
      catchError((error: unknown) => throwError(() => normalizeError(error)))
    )
  }
}

/** Generate a correlation id without assuming `crypto.randomUUID` is available. */
export function generateCorrelationId(): string {
  const cryptoRef = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (typeof cryptoRef?.randomUUID === 'function') return cryptoRef.randomUUID()
  return `cockpit-v2-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function isEnvelope(value: unknown): value is { data: unknown } {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.prototype.hasOwnProperty.call(value, 'data')
  )
}

/** Normalize an HTTP/transport failure into a structured, serializable error. */
export function normalizeError(error: unknown): FactoryApiError {
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
          : error.message || 'Factory API request failed'
    return { code, message, status: error.status, raw: error }
  }
  if (error instanceof Error) {
    return { code: 'UNKNOWN_ERROR', message: error.message, status: 0, raw: error }
  }
  return { code: 'UNKNOWN_ERROR', message: 'Factory API request failed', status: 0, raw: error }
}
