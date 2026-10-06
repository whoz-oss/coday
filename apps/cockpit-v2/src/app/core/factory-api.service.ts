import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http'
import { Injectable, inject } from '@angular/core'
import { Observable, catchError, map, of, throwError } from 'rxjs'
import { AgentQuestion, AllowedAction, GetActionsResponse, WorkflowBlocker } from './models'

export type WorkflowState = 'active' | 'removed'

/** Normalized error surfaced by {@link FactoryApiService}. */
export interface FactoryApiError {
  code: string
  message: string
  status: number
  details?: Record<string, unknown>
  raw: unknown
}

/** Shape of the `GET /api/factory/workflows` payload (inside the `{ data }` envelope). */
export interface WorkflowListPayload {
  namespaceId?: string
  state?: string
  items?: unknown[]
  [key: string]: unknown
}

/** Report returned by the admin garbage-collection command. */
export interface GcReport {
  reclaimedStagingKeys?: string[]
  scannedBlobKeys?: string[]
  scannedMetadataRows?: number
  anomalies?: string[]
  timestamp?: string
  [key: string]: unknown
}

/** Result of an admin artifact purge. */
export interface PurgeResult {
  status?: string
  artifactId?: string
  id?: string
  reason?: string
  metadata?: { size?: number; [key: string]: unknown }
  [key: string]: unknown
}

/** Result of an admin legal-hold command. */
export interface LegalHoldResult {
  status?: string
  id?: string
  artifactId?: string
  legalHold?: boolean
  legalHoldReason?: string
  reason?: string
  [key: string]: unknown
}

/** One registered workflow definition. */
export interface WorkflowDefinition {
  workflowType?: string
  version?: string
  definitionHash?: string
  [key: string]: unknown
}

/** One step of a full workflow definition (`GET /api/factory/workflow-definitions/:type/:version`). */
export interface FullWorkflowStep {
  id: string
  name: string
  responsibility?: { kind?: string; name?: string }
  dependsOn?: string[]
  [key: string]: unknown
}

/** Unwrapped payload of `GET /api/factory/workflow-definitions/:workflowType/:version`. */
export interface FullWorkflowDefinition {
  schemaVersion?: string
  workflowType?: string
  version?: string
  title?: string
  trustedExecution?: boolean
  steps?: FullWorkflowStep[]
  [key: string]: unknown
}

/** One AgentOS namespace exposed by `GET /api/namespaces`. */
export interface NamespaceItem {
  id?: string
  name?: string
  namespaceId?: string
  [key: string]: unknown
}

/** Bounded caller-owned input of the canonical create-run use case. */
export interface CreateWorkflowRunRequest {
  workflowType: string
  title?: string
  initialRequest?: string
  parameters?: { ticket?: string }
}

/** Honest durable creation/submission result returned by Factory. */
export interface CreateWorkflowRunResponse {
  workflowId: string
  title: string
  created: boolean
  queued: boolean
  idempotent: boolean
  submissionId: string
  submissionStatus: string
  status: string
  revision: number
}

/** @deprecated Compatibility request for callers still using the old start route. */
export interface StartWorkflowRequest {
  workflow: { workflowId: string; workflowType: string; title: string; ticket?: string }
  execution: { namespaceId: string; runtimeId: string; kind: string; agentId: string }
  controllerRequest: string
}

/** Body of `POST /api/factory/workflows/:id/run` (legacy explicit run). */
export interface RunWorkflowRequest {
  namespaceId: string
  ticket?: string
  repoRoot?: string
}

/** Unwrapped `{ data }` payload of `POST /api/factory/workflows/:id/run`. */
export interface RunWorkflowResponse {
  status?: string
  submissionId?: string
  workflowId?: string
  [key: string]: unknown
}

/** Machine codes meaning the workflow instance already exists (start is idempotent). */
export const WORKFLOW_CONFLICT_CODES = ['WORKFLOW_IDENTITY_CONFLICT', 'WORKFLOW_ALREADY_EXISTS'] as const

/**
 * True when a start failure only means the instance is already materialized
 * (identity conflict / already exists / HTTP 409), in which case the /run phase
 * may proceed for that existing instance.
 */
export function isWorkflowConflict(error: FactoryApiError | null | undefined): boolean {
  if (!error) return false
  if (error.status === 409) return true
  return (WORKFLOW_CONFLICT_CODES as readonly string[]).includes(error.code)
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
   * GET `/api/factory/workflows/:id/actions[?namespaceId=…]`.
   *
   * Authoritative read of what a governed workflow permits right now. Unwraps
   * the `{ data: { allowedActions, blockers } }` envelope and defensively
   * normalizes both lists to arrays so the caller can render conditionally.
   */
  getActions(workflowId: string, namespaceId?: string): Observable<GetActionsResponse> {
    return this.request<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/actions`,
      {},
      namespaceId
    ).pipe(
      map((payload) => {
        const obj = (typeof payload === 'object' && payload !== null ? payload : {}) as {
          allowedActions?: unknown
          blockers?: unknown
        }
        return {
          allowedActions: Array.isArray(obj.allowedActions) ? (obj.allowedActions as AllowedAction[]) : [],
          blockers: Array.isArray(obj.blockers) ? (obj.blockers as WorkflowBlocker[]) : [],
        }
      })
    )
  }

  /** Canonical Factory-owned creation and durable submission. */
  createWorkflowRun(
    payload: CreateWorkflowRunRequest,
    namespaceId: string,
    idempotencyKey: string
  ): Observable<CreateWorkflowRunResponse> {
    return this.post<CreateWorkflowRunResponse>(
      '/api/factory/workflows',
      payload,
      namespaceId,
      undefined,
      idempotencyKey
    )
  }

  /**
   * POST `/api/factory/workflows/:id/interactions/:interactionId/reply`.
   * `actionId` and `expectedRevision` come from the backend `reply` allowed
   * action; the cockpit never invents them.
   */
  replyInteraction(
    workflowId: string,
    interactionId: string,
    payload: { actionId?: string; text?: string; expectedRevision?: number },
    namespaceId?: string
  ): Observable<unknown> {
    return this.post<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/interactions/${encodeURIComponent(interactionId)}/reply`,
      payload,
      namespaceId
    )
  }

  /**
   * Answer the authoritative AgentOS queryUser question through Factory's
   * trusted projection endpoint. Factory forwards to AgentOS' standard
   * POST /api/cases/{caseId}/messages contract with answerToEventId.
   */
  answerAgentQuestion(
    workflowId: string,
    questionEventId: string,
    payload: { stepId: string; answer: string },
    namespaceId?: string
  ): Observable<unknown> {
    return this.post<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/agent-questions/${encodeURIComponent(questionEventId)}/answer`,
      payload,
      namespaceId
    )
  }

  /** @deprecated Legacy Factory-owned step-question endpoint, kept for explicit old data only. */
  answerAgentStepQuestion(
    workflowId: string,
    interactionId: string,
    payload: { expectedRevision: number; answer: string }
  ): Observable<unknown> {
    return this.post<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/agent-step-questions/${encodeURIComponent(interactionId)}/answer`,
      payload
    )
  }

  /**
   * Derive the active AgentOS questions from the authoritative attempts and
   * AgentOS case-event histories exposed by Factory. This read is replay-safe:
   * reconnect simply re-fetches durable events and AnswerEvent correlation
   * closes the question in both UIs.
   */
  getAgentQuestions(workflowId: string, namespaceId?: string): Observable<AgentQuestion[]> {
    return this.request<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/agent-questions`,
      {},
      namespaceId
    ).pipe(map((payload) => (Array.isArray(payload) ? (payload as AgentQuestion[]) : [])))
  }

  /** POST `/api/factory/workflows/:id/retries` (opens a retry for a blocked step). */
  openRetry(
    workflowId: string,
    payload: { stepId: string; expectedRevision?: number; reasonCode?: string },
    namespaceId?: string
  ): Observable<unknown> {
    return this.post<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/retries`, payload, namespaceId)
  }

  /** POST `/api/factory/workflows/:id/attempts/:attemptId/cancel`. */
  cancelAttempt(
    workflowId: string,
    attemptId: string,
    payload: { expectedRevision?: number; reason?: string },
    namespaceId?: string
  ): Observable<unknown> {
    return this.post<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/attempts/${encodeURIComponent(attemptId)}/cancel`,
      payload,
      namespaceId
    )
  }

  /** POST `/api/factory/workflows/:id/cost/continue`. */
  continueCost(
    workflowId: string,
    payload?: { expectedThreshold?: number },
    namespaceId?: string
  ): Observable<unknown> {
    return this.post<unknown>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/cost/continue`,
      payload ?? {},
      namespaceId
    )
  }

  /** POST `/api/factory/workflows/:id/cost/stop`. */
  stopCost(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.post<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/cost/stop`, {}, namespaceId)
  }

  /** DELETE `/api/factory/workflows/:id` — soft-removes a workflow. */
  removeWorkflow(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.delete<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}`, namespaceId)
  }

  /** POST `/api/factory/workflows/:id/restore` — restores a removed workflow. */
  restoreWorkflow(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.post<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/restore`, {}, namespaceId)
  }

  /** POST `/api/factory/workflows/:id/purge` — permanently purges a removed workflow. */
  purgeWorkflow(workflowId: string, namespaceId?: string): Observable<unknown> {
    return this.post<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/purge`, {}, namespaceId)
  }

  /** POST `/api/factory/admin/artifacts/gc` (admin-only garbage collection). */
  runGarbageCollection(body?: { dryRun?: boolean }, namespaceId?: string): Observable<unknown> {
    return this.post<unknown>('/api/factory/admin/artifacts/gc', body ?? {}, namespaceId)
  }

  /** POST `/api/factory/admin/artifacts/:artifactId/purge` (admin-only, destructive). */
  purgeArtifact(artifactId: string, body?: { reason?: string }, namespaceId?: string): Observable<unknown> {
    const path = `/api/factory/admin/artifacts/${encodeURIComponent(artifactId)}/purge`
    return this.post<unknown>(path, body ?? {}, namespaceId)
  }

  /** POST `/api/factory/admin/artifacts/:artifactId/legal-hold` (admin-only). */
  setLegalHold(
    artifactId: string,
    body: { legalHold: boolean; reason?: string },
    namespaceId?: string
  ): Observable<unknown> {
    const path = `/api/factory/admin/artifacts/${encodeURIComponent(artifactId)}/legal-hold`
    return this.post<unknown>(path, body, namespaceId)
  }

  /** GET `/api/factory/workflow-definitions[?namespaceId=…]`. */
  getWorkflowDefinitions(namespaceId?: string): Observable<unknown> {
    return this.request<unknown>('/api/factory/workflow-definitions', {}, namespaceId)
  }

  /**
   * GET `/api/factory/workflow-definitions/:workflowType/:version[?namespaceId=…]`.
   *
   * Both path segments are URL-encoded. The `{ data }` envelope is unwrapped by
   * {@link request}; the payload is then defensively normalized so a
   * double-wrapped envelope or a direct object both resolve to a
   * {@link FullWorkflowDefinition}.
   */
  getWorkflowDefinition(
    workflowType: string,
    version: string,
    namespaceId?: string
  ): Observable<FullWorkflowDefinition> {
    const path = `/api/factory/workflow-definitions/${encodeURIComponent(workflowType)}/${encodeURIComponent(version)}`
    return this.request<unknown>(path, {}, namespaceId).pipe(map((payload) => normalizeFullWorkflowDefinition(payload)))
  }

  /**
   * GET `/api/namespaces` → AgentOS namespaces. Unwraps a raw array or a
   * `{ items: […] }` payload and degrades gracefully to `[]` when the endpoint
   * is missing, unavailable or returns an error.
   */
  getNamespaces(): Observable<NamespaceItem[]> {
    return this.request<unknown>('/api/namespaces', {}).pipe(
      map((payload) => {
        if (Array.isArray(payload)) return payload as NamespaceItem[]
        const items = (payload as { items?: unknown } | null)?.items
        return Array.isArray(items) ? (items as NamespaceItem[]) : []
      }),
      catchError(() => of([] as NamespaceItem[]))
    )
  }

  /**
   * POST `/api/factory/workflows/:id/start` — materializes the workflow instance
   * from a definition. A `WORKFLOW_IDENTITY_CONFLICT` / `WORKFLOW_ALREADY_EXISTS`
   * (or HTTP 409) failure is NOT swallowed here; the caller decides, via
   * {@link isWorkflowConflict}, whether it can proceed to the /run phase.
   */
  startWorkflow(workflowId: string, payload: StartWorkflowRequest, namespaceId?: string): Observable<unknown> {
    return this.post<unknown>(`/api/factory/workflows/${encodeURIComponent(workflowId)}/start`, payload, namespaceId)
  }

  /** POST `/api/factory/workflows/:id/run` — triggers the durable async run (202 Accepted). */
  runWorkflow(workflowId: string, payload: RunWorkflowRequest, namespaceId?: string): Observable<RunWorkflowResponse> {
    return this.post<RunWorkflowResponse>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/run`,
      payload,
      namespaceId
    )
  }

  /**
   * POST `/api/factory/workflow-definitions/upload` as `multipart/form-data`.
   * The `Content-Type` header is deliberately NOT set so the browser/HttpClient
   * can attach the multipart boundary itself.
   */
  uploadWorkflowDefinition(file: File, namespaceId?: string): Observable<unknown> {
    const formData = new FormData()
    formData.append('file', file, file.name || 'definition.json')
    return this.postFormData<unknown>('/api/factory/workflow-definitions/upload', formData, namespaceId)
  }

  /** DELETE `/api/factory/workflow-definitions/:workflowType/:version[?namespaceId=…]`. */
  deleteWorkflowDefinition(workflowType: string, version: string, namespaceId?: string): Observable<unknown> {
    const path = `/api/factory/workflow-definitions/${encodeURIComponent(workflowType)}/${encodeURIComponent(version)}`
    return this.delete<unknown>(path, namespaceId)
  }

  /**
   * Issue a POST request and unwrap the response. Mirrors {@link request}:
   * an `X-Correlation-Id` is always sent, the optional namespace is threaded
   * as both the `namespaceId` query param and the `X-Namespace-Id` header, the
   * `{ data }` envelope is unwrapped and failures are normalized.
   */
  private post<T>(
    path: string,
    body: unknown,
    namespaceId?: string,
    correlationId?: string,
    idempotencyKey?: string
  ): Observable<T> {
    let params = new HttpParams()
    let headers = new HttpHeaders()
      .set('X-Correlation-Id', correlationId ?? generateCorrelationId())
      .set('Content-Type', 'application/json')
    if (idempotencyKey) headers = headers.set('Idempotency-Key', idempotencyKey)
    const namespace = namespaceId?.trim()
    if (namespace) {
      headers = headers.set('X-Namespace-Id', namespace)
      params = params.set('namespaceId', namespace)
    }

    return this.http.post<unknown>(path, body, { params, headers }).pipe(
      map((payload) => (isEnvelope(payload) ? (payload.data as T) : (payload as T))),
      catchError((error: unknown) => throwError(() => normalizeError(error)))
    )
  }

  /**
   * Issue a `multipart/form-data` POST and unwrap the response. Mirrors
   * {@link post} but never sets `Content-Type` (the boundary is owned by the
   * browser/HttpClient FormData handling).
   */
  private postFormData<T>(path: string, formData: FormData, namespaceId?: string): Observable<T> {
    let params = new HttpParams()
    let headers = new HttpHeaders().set('X-Correlation-Id', generateCorrelationId())
    const namespace = namespaceId?.trim()
    if (namespace) {
      headers = headers.set('X-Namespace-Id', namespace)
      params = params.set('namespaceId', namespace)
    }

    return this.http.post<unknown>(path, formData, { params, headers }).pipe(
      map((payload) => (isEnvelope(payload) ? (payload.data as T) : (payload as T))),
      catchError((error: unknown) => throwError(() => normalizeError(error)))
    )
  }

  /** Issue a DELETE request and unwrap the response. */
  private delete<T>(path: string, namespaceId?: string): Observable<T> {
    let params = new HttpParams()
    let headers = new HttpHeaders().set('X-Correlation-Id', generateCorrelationId())
    const namespace = namespaceId?.trim()
    if (namespace) {
      headers = headers.set('X-Namespace-Id', namespace)
      params = params.set('namespaceId', namespace)
    }

    return this.http.delete<unknown>(path, { params, headers }).pipe(
      map((payload) => (isEnvelope(payload) ? (payload.data as T) : (payload as T))),
      catchError((error: unknown) => throwError(() => normalizeError(error)))
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

/**
 * Defensively coerce a workflow-definition payload into a plain object. A
 * payload that is still wrapped in one or more `{ data }` envelopes is
 * unwrapped; anything that is not an object degrades to `{}`.
 */
function normalizeFullWorkflowDefinition(payload: unknown): FullWorkflowDefinition {
  let normalized: unknown = payload
  while (isEnvelope(normalized)) {
    normalized = normalized.data
  }
  return (typeof normalized === 'object' && normalized !== null ? normalized : {}) as FullWorkflowDefinition
}

/** Normalize an HTTP/transport failure into a structured, serializable error. */
export function normalizeError(error: unknown): FactoryApiError {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as {
      error?: { code?: unknown; message?: unknown; details?: unknown }
      code?: unknown
      message?: unknown
      details?: unknown
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
    const detailsRaw = envelope?.details ?? body?.details
    const details =
      typeof detailsRaw === 'object' && detailsRaw !== null && !Array.isArray(detailsRaw)
        ? (detailsRaw as Record<string, unknown>)
        : undefined
    return { code, message, status: error.status, ...(details ? { details } : {}), raw: error }
  }
  if (error instanceof Error) {
    return { code: 'UNKNOWN_ERROR', message: error.message, status: 0, raw: error }
  }
  return { code: 'UNKNOWN_ERROR', message: 'Factory API request failed', status: 0, raw: error }
}
