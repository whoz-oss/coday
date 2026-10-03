import { HttpClient, HttpErrorResponse, HttpParams, HttpResponse } from '@angular/common/http'
import { Injectable, inject, signal } from '@angular/core'
import { Observable, catchError, map, of, tap, throwError } from 'rxjs'
import {
  AllowedActionDto,
  ControllerHistoryDto,
  ControllerHistoryEntryDto,
  FactoryCommandAckDto,
  HumanActionId,
  HumanActionRequiredDto,
  PlanChangeOperationDto,
  PlanChangeProposalDto,
  StepAttemptDto,
  StepLane,
  WorkflowActionsResponseDto,
  WorkflowBlockerDto,
  WorkflowDetailDto,
  WorkflowListDto,
  WorkflowStepDto,
  WorkflowSummaryDto,
  WorkstreamDto,
  WorkstreamProjectionResponseDto,
} from '../models/workstream.model'
import { WorkstreamMockService } from './workstream-mock.service'

/** Default base path of the Factory control-plane HTTP surface. */
export const FACTORY_API_BASE = '/api/factory'

/** Optional configuration for the workstream service (dev/test toggle). */
export interface FactoryWorkstreamConfig {
  /**
   * When `true`, every call delegates to {@link WorkstreamMockService} instead of
   * performing real HTTP. Defaults to `false` so production wiring targets the
   * real `/api/factory/**` endpoints.
   */
  useMock?: boolean
  /** Override the API base path (defaults to {@link FACTORY_API_BASE}). */
  baseUrl?: string
}

/** Normalized, serializable Factory/transport error surfaced by the service. */
export interface FactoryApiError {
  code: string
  message: string
  status: number
  details?: Record<string, unknown>
  raw?: unknown
}

/**
 * Angular HTTP client for the Factory Workstream control plane (`/api/factory/**`).
 *
 * Every real call:
 *  - targets `/api/factory/**` (relative, same-origin behind the gateway);
 *  - unwraps the Factory success envelope `{ data: T }` (§2.3) and normalizes the
 *    error envelope `{ error: { code, message, details } }` into a {@link FactoryApiError};
 *  - captures freshness from the response (`ETag` / `x-factory-revision` headers or a
 *    `revision` / `workstreamRevision` payload field) and exposes it through
 *    {@link lastRevision}, {@link lastETag} and {@link lastSyncAsOf}.
 *
 * A dev/test toggle ({@link useMock}, default `false`) lets the cockpit fall back to
 * the in-memory {@link WorkstreamMockService} without changing any consumer.
 */
@Injectable({ providedIn: 'root' })
export class FactoryWorkstreamService {
  private readonly http = inject(HttpClient)
  private readonly mock = inject(WorkstreamMockService)

  /** When `true`, all reads/commands delegate to the mock service (no HTTP). */
  readonly useMock = signal(false)

  /** Last authoritative revision observed from a response (header or payload). */
  readonly lastRevision = signal<number>(0)

  /** Last quoted ETag (quotes stripped) observed from a response, when present. */
  readonly lastETag = signal<string | null>(null)

  /** ISO timestamp of the last successful real HTTP synchronization. */
  readonly lastSyncAsOf = signal<string | null>(null)

  private baseUrl = FACTORY_API_BASE

  /** Configure the service (base URL / mock toggle). */
  configure(config: FactoryWorkstreamConfig): void {
    if (typeof config.useMock === 'boolean') this.useMock.set(config.useMock)
    if (config.baseUrl) this.baseUrl = config.baseUrl.replace(/\/+$/, '')
  }

  /** Toggle the mock fallback (dev/test). */
  setUseMock(enabled: boolean): void {
    this.useMock.set(enabled)
  }

  // ----- reads ---------------------------------------------------------

  /** `GET /api/factory/workstreams/{workstreamId}` — workstream registry entry. */
  getWorkstream(workstreamId: string): Observable<WorkstreamDto> {
    if (this.useMock()) return this.mock.getWorkstream(workstreamId)
    return this.request<Record<string, unknown>>('GET', `/workstreams/${encodeURIComponent(workstreamId)}`).pipe(
      map((raw) => toWorkstreamDto(raw, workstreamId))
    )
  }

  /**
   * `GET /api/factory/workstreams/{workstreamId}/projection` — Phase 5 aggregated,
   * read-only projection. The stable `workstreamRevision` is returned both in the body
   * and as the HTTP `ETag` header.
   */
  getWorkstreamProjection(workstreamId: string): Observable<WorkstreamProjectionResponseDto> {
    if (this.useMock()) {
      return this.mock.getWorkstream(workstreamId).pipe(
        map((ws) => ({
          workstreamId: ws.workstreamId,
          workstreamRevision: ws.revision,
          revision: ws.revision,
          workflows: [] as WorkflowSummaryDto[],
        }))
      )
    }
    return this.request<WorkstreamProjectionResponseDto>(
      'GET',
      `/workstreams/${encodeURIComponent(workstreamId)}/projection`
    )
  }

  /** `GET /api/factory/workflows?state=active` — workflow projection summaries. */
  listWorkflows(workstreamId: string): Observable<WorkflowListDto> {
    if (this.useMock()) return this.mock.listWorkflows(workstreamId)
    const query: Record<string, string | undefined> = { state: 'active' }
    if (workstreamId) query['workstreamId'] = workstreamId
    return this.request<unknown>('GET', '/workflows', query).pipe(map((payload) => toWorkflowList(payload)))
  }

  /** `GET /api/factory/workflows/{workflowId}` — workflow projection/state. */
  getWorkflow(workflowId: string): Observable<WorkflowDetailDto> {
    if (this.useMock()) return this.mock.getWorkflow(workflowId)
    return this.request<Record<string, unknown>>('GET', `/workflows/${encodeURIComponent(workflowId)}`).pipe(
      map((raw) => toWorkflowDetailDto(raw, workflowId))
    )
  }

  /** `GET /api/factory/workflows/{workflowId}/attempts?stepId=…` — durable attempts. */
  getStepAttempts(workflowId: string, stepId: string): Observable<StepAttemptDto[]> {
    if (this.useMock()) return this.mock.getStepAttempts(workflowId, stepId)
    return this.request<unknown>('GET', `/workflows/${encodeURIComponent(workflowId)}/attempts`, { stepId }).pipe(
      map((payload) => toAttemptList(payload).filter((attempt) => !stepId || attempt.stepId === stepId))
    )
  }

  /**
   * Authoritative allowed actions + blockers (`GET /workflows/{workflowId}/actions`).
   * The cockpit derives its controls strictly from `allowedActions`.
   */
  getAllowedActions(workflowId: string): Observable<WorkflowActionsResponseDto> {
    if (this.useMock()) return this.mock.getAllowedActions(workflowId)
    return this.request<unknown>('GET', `/workflows/${encodeURIComponent(workflowId)}/actions`).pipe(
      map((payload) => toActionsResponse(payload, workflowId))
    )
  }

  /** Blockers section of the authoritative actions read. */
  getBlockers(workflowId: string): Observable<WorkflowBlockerDto[]> {
    if (this.useMock()) return this.mock.getBlockers(workflowId)
    return this.getAllowedActions(workflowId).pipe(map((response) => response.blockers))
  }

  /**
   * Open human interactions (`GET /workflows/{workflowId}/interactions?state=open`).
   * Degrades to `[]` on a missing/unsupported endpoint rather than failing the view.
   */
  getRequiredHumanActions(workflowId: string): Observable<HumanActionRequiredDto[]> {
    if (this.useMock()) return this.mock.getRequiredHumanActions(workflowId)
    return this.request<unknown>('GET', `/workflows/${encodeURIComponent(workflowId)}/interactions`, {
      state: 'open',
    }).pipe(
      map((payload) => toInteractionList(payload)),
      catchError(() => of([] as HumanActionRequiredDto[]))
    )
  }

  /**
   * Plan-change proposals (`GET /plan-change-proposals?workflowId=…`). The listing is
   * best-effort and degrades to `[]` when unavailable.
   */
  getPlanChangeProposals(workflowId: string): Observable<PlanChangeProposalDto[]> {
    if (this.useMock()) return this.mock.getPlanChangeProposals(workflowId)
    return this.request<unknown>('GET', '/plan-change-proposals', { workflowId }).pipe(
      map((payload) => toProposalList(payload, workflowId)),
      catchError(() => of([] as PlanChangeProposalDto[]))
    )
  }

  /**
   * Controller case history (`GET /workstreams/{workstreamId}/controller-case/history`).
   * A 404 (no controller case yet) degrades to an empty history.
   */
  getControllerHistory(workflowId: string, workstreamId?: string): Observable<ControllerHistoryDto> {
    if (this.useMock()) return this.mock.getControllerHistory(workflowId)
    const targetWorkstream = workstreamId ?? workflowId
    return this.request<Record<string, unknown>>(
      'GET',
      `/workstreams/${encodeURIComponent(targetWorkstream)}/controller-case/history`
    ).pipe(
      map((raw) => toControllerHistory(raw, workflowId)),
      catchError(() => of(emptyControllerHistory(workflowId)))
    )
  }

  /** Lane metadata: view-model only, there is no real Factory source (mock-backed). */
  getStepLanes(workflowId: string): Observable<Record<string, StepLane>> {
    return this.mock.getStepLanes(workflowId)
  }

  // ----- commands ------------------------------------------------------

  /**
   * `POST /workflows/{workflowId}/retries` — request an agent retry (control-plane /
   * human capability). Opens an interaction which the Factory decides on.
   */
  requestAgentRetry(
    workflowId: string,
    stepId: string,
    expectedRevision: number,
    reasonCode: string
  ): Observable<FactoryCommandAckDto> {
    if (this.useMock()) {
      return this.mock
        .requestAgentRetry(workflowId, stepId, expectedRevision, reasonCode)
        .pipe(map((ack) => ack as FactoryCommandAckDto))
    }
    return this.request<FactoryCommandAckDto>(
      'POST',
      `/workflows/${encodeURIComponent(workflowId)}/retries`,
      undefined,
      {
        stepId,
        expectedRevision,
        reasonCode,
      }
    )
  }

  /**
   * `POST /workflows/{workflowId}/interactions/{interactionId}/reply` — human reply.
   * `actionId` and `expectedRevision` come from the backend allowed action.
   */
  respondToInteraction(
    workflowId: string,
    interactionId: string,
    actionId: HumanActionId | string,
    expectedRevision?: number
  ): Observable<FactoryCommandAckDto> {
    if (this.useMock()) {
      return this.mock
        .respondToInteraction(workflowId, interactionId, actionId as HumanActionId)
        .pipe(map((ack) => ack as FactoryCommandAckDto))
    }
    const body: Record<string, unknown> = { actionId }
    if (typeof expectedRevision === 'number') body['expectedRevision'] = expectedRevision
    return this.request<FactoryCommandAckDto>(
      'POST',
      `/workflows/${encodeURIComponent(workflowId)}/interactions/${encodeURIComponent(interactionId)}/reply`,
      undefined,
      body
    )
  }

  /**
   * `POST /plan-change-proposals/{proposalId}/decide?workflowId=…` — record a
   * governance decision on a plan-change proposal.
   */
  decidePlanChange(
    proposalId: string,
    decision: 'approve' | 'reject',
    options?: { workflowId?: string; expectedRevision?: number }
  ): Observable<FactoryCommandAckDto> {
    if (this.useMock()) {
      return this.mock.decidePlanChange(proposalId, decision).pipe(map((ack) => ack as FactoryCommandAckDto))
    }
    const query: Record<string, string | number | undefined> = { workflowId: options?.workflowId }
    const body: Record<string, unknown> = { decision }
    if (typeof options?.expectedRevision === 'number') body['expectedRevision'] = options.expectedRevision
    return this.request<FactoryCommandAckDto>(
      'POST',
      `/plan-change-proposals/${encodeURIComponent(proposalId)}/decide`,
      query,
      body
    )
  }

  // ----- internals -----------------------------------------------------

  /**
   * Issue a GET/POST, unwrap the `{ data }` envelope, capture freshness and
   * normalize failures into a {@link FactoryApiError}.
   */
  private request<T>(
    method: 'GET' | 'POST',
    path: string,
    query?: Record<string, string | number | undefined>,
    body?: unknown
  ): Observable<T> {
    let params = new HttpParams()
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined && `${value}`.length > 0) params = params.set(key, `${value}`)
    }
    const url = `${this.baseUrl}${path}`
    const response$ =
      method === 'GET'
        ? this.http.get<unknown>(url, { params, observe: 'response' })
        : this.http.post<unknown>(url, body ?? {}, { params, observe: 'response' })

    return response$.pipe(
      tap((response) => this.captureFreshness(response)),
      map((response) => unwrapEnvelope<T>(response.body)),
      catchError((error: unknown) => throwError(() => normalizeFactoryError(error)))
    )
  }

  /** Capture `ETag` / revision headers and the sync timestamp from a response. */
  private captureFreshness(response: HttpResponse<unknown>): void {
    const revisionHeader = response.headers.get('x-factory-revision') ?? response.headers.get('x-workstream-revision')
    const headerRevision = revisionHeader !== null ? Number(revisionHeader) : undefined
    const payload = asRecord(unwrapEnvelope<unknown>(response.body))
    const bodyRevision = asNumber(payload?.['revision']) ?? asNumber(payload?.['workstreamRevision'])
    const revision = headerRevision !== undefined && Number.isFinite(headerRevision) ? headerRevision : bodyRevision
    if (typeof revision === 'number' && Number.isFinite(revision)) this.lastRevision.set(revision)

    const etag = response.headers.get('etag')
    if (etag !== null && etag.length > 0) this.lastETag.set(etag.replace(/^W\//, '').replace(/"/g, ''))

    const date = response.headers.get('date')
    this.lastSyncAsOf.set(date ?? new Date().toISOString())
  }
}

/** Unwrap the Factory success envelope `{ data: T }`, passing bare payloads through. */
export function unwrapEnvelope<T>(payload: unknown): T {
  if (isRecord(payload) && Object.prototype.hasOwnProperty.call(payload, 'data')) {
    return payload['data'] as T
  }
  return payload as T
}

/** Normalize an HTTP/transport failure into a structured {@link FactoryApiError}. */
export function normalizeFactoryError(error: unknown): FactoryApiError {
  if (error instanceof HttpErrorResponse) {
    const body = asRecord(error.error)
    const envelope = asRecord(body?.['error'])
    const code = asString(envelope?.['code']) ?? asString(body?.['code']) ?? `HTTP_${error.status}`
    const message =
      asString(envelope?.['message']) ?? asString(body?.['message']) ?? error.message ?? 'Factory request failed'
    const details = asRecord(envelope?.['details']) ?? undefined
    return { code, message, status: error.status, details, raw: error }
  }
  if (error instanceof Error) return { code: 'UNKNOWN_ERROR', message: error.message, status: 0, raw: error }
  return { code: 'UNKNOWN_ERROR', message: 'Factory request failed', status: 0, raw: error }
}

// ----- defensive mapping helpers ---------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return isRecord(value) ? value : null
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function asNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value)
  return undefined
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

/** Extract an array from a bare array, a `{ [key]: [...] }` block or `items`/`data` wrappers. */
function listFrom(payload: unknown, key: string): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asRecord(payload)
  if (!obj) return []
  if (Array.isArray(obj[key])) return obj[key] as unknown[]
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}

function toWorkstreamDto(raw: Record<string, unknown>, fallbackId: string): WorkstreamDto {
  return {
    workstreamId: asString(raw['workstreamId']) ?? asString(raw['slug']) ?? fallbackId,
    organizationId: asString(raw['organizationId']) ?? '',
    name: asString(raw['name']) ?? asString(raw['title']) ?? fallbackId,
    status: asString(raw['status']) ?? 'unknown',
    revision: asNumber(raw['revision']) ?? 0,
  }
}

function toWorkflowList(payload: unknown): WorkflowListDto {
  const items = listFrom(payload, 'items') as WorkflowSummaryDto[]
  const obj = asRecord(payload)
  const nextCursor = asString(obj?.['nextCursor']) ?? null
  return { items, nextCursor }
}

function toWorkflowDetailDto(raw: Record<string, unknown>, fallbackId: string): WorkflowDetailDto {
  const stepsRaw = asArray(raw['steps'])
  const steps: WorkflowStepDto[] = stepsRaw.map((entry) => {
    const step = asRecord(entry) ?? {}
    return {
      stepId: asString(step['stepId']) ?? '',
      status: asString(step['status']) ?? 'unknown',
      revision: asNumber(step['revision']) ?? 0,
    }
  })
  const blockers = toBlockerList(raw['blockers'])
  return {
    state: (asString(raw['state']) ?? asString(raw['lifecycleState']) ?? 'existing') as WorkflowDetailDto['state'],
    workflowId: asString(raw['workflowId']) ?? fallbackId,
    revision: asNumber(raw['revision']) ?? 0,
    workflowType: asString(raw['workflowType']) ?? '',
    status: asString(raw['status']) ?? 'unknown',
    steps,
    blockers,
  }
}

function toBlockerList(value: unknown): WorkflowBlockerDto[] {
  return asArray(value).map((entry) => {
    const blocker = asRecord(entry) ?? {}
    return {
      code: (asString(blocker['code']) ?? 'UNKNOWN_RUNTIME') as WorkflowBlockerDto['code'],
      stepId: asString(blocker['stepId']) ?? null,
      message: asString(blocker['message']) ?? '',
    }
  })
}

function toAttemptList(payload: unknown): StepAttemptDto[] {
  return listFrom(payload, 'items').map((entry, index) => {
    const raw = asRecord(entry) ?? {}
    return {
      attemptId: asString(raw['attemptId']) ?? `attempt-${index + 1}`,
      stepId: asString(raw['stepId']) ?? '',
      attemptNumber: asNumber(raw['attemptNumber']) ?? 0,
      agentName: asString(raw['agentName']) ?? '',
      status: (asString(raw['status']) ?? 'unknown') as StepAttemptDto['status'],
      caseId: asString(raw['caseId']) ?? '',
      failureCode: asString(raw['failureCode']) ?? null,
      resultEvidenceId: asString(raw['resultEvidenceId']) ?? null,
      revision: asNumber(raw['revision']) ?? 0,
      createdAt: asString(raw['createdAt']) ?? '',
      startedAt: asString(raw['startedAt']) ?? null,
      completedAt: asString(raw['completedAt']) ?? null,
    }
  })
}

function toActionsResponse(payload: unknown, workflowId: string): WorkflowActionsResponseDto {
  const obj = asRecord(payload) ?? {}
  return {
    workflowId: asString(obj['workflowId']) ?? workflowId,
    revision: asNumber(obj['revision']),
    allowedActions: toAllowedActionList(obj['allowedActions'] ?? payload),
    blockers: toBlockerList(obj['blockers']),
  }
}

function toAllowedActionList(value: unknown): AllowedActionDto[] {
  return asArray(value).map((entry) => {
    const raw = asRecord(entry) ?? {}
    const action: AllowedActionDto = {}
    const type = asString(raw['type']) ?? asString(raw['id']) ?? asString(raw['kind'])
    if (type) action.type = type
    const id = asString(raw['id'])
    if (id) action.id = id
    const label = asString(raw['label'])
    if (label) action.label = label
    const kind = asString(raw['kind'])
    if (kind) action.kind = kind
    const interactionId = asString(raw['interactionId'])
    if (interactionId) action.interactionId = interactionId
    const stepId = asString(raw['stepId'])
    if (stepId) action.stepId = stepId
    const attemptId = asString(raw['attemptId'])
    if (attemptId) action.attemptId = attemptId
    const caseId = asString(raw['caseId'])
    if (caseId) action.caseId = caseId
    const questionEventId = asString(raw['questionEventId'])
    if (questionEventId) action.questionEventId = questionEventId
    const expectedRevision = asNumber(raw['expectedRevision'])
    if (expectedRevision !== undefined) action.expectedRevision = expectedRevision
    return action
  })
}

function toInteractionList(payload: unknown): HumanActionRequiredDto[] {
  return listFrom(payload, 'items').map((entry, index) => {
    const raw = asRecord(entry) ?? {}
    const detail = asRecord(raw['payload']) ?? {}
    const actionsRaw = asArray(detail['actions'] ?? raw['actions'])
    return {
      interactionId: asString(raw['interactionId']) ?? asString(raw['id']) ?? `interaction-${index + 1}`,
      stepId: asString(raw['stepId']) ?? asString(detail['stepId']) ?? '',
      questionEventId: asString(detail['questionEventId'] ?? raw['questionEventId']) ?? null,
      prompt: asString(detail['prompt']) ?? asString(detail['question']) ?? asString(raw['prompt']) ?? '',
      actions: actionsRaw.map((action) => {
        const a = asRecord(action) ?? {}
        return {
          id: (asString(a['id']) ?? 'approve') as HumanActionRequiredDto['actions'][number]['id'],
          label: asString(a['label']) ?? '',
        }
      }),
      expectedRevision:
        asNumber(raw['revision']) ?? asNumber(detail['expectedRevision']) ?? asNumber(raw['expectedRevision']) ?? 0,
    }
  })
}

function toProposalList(payload: unknown, workflowId: string): PlanChangeProposalDto[] {
  return listFrom(payload, 'items').map((entry) => {
    const raw = asRecord(entry) ?? {}
    const operationsRaw = asArray(raw['operations'])
    const operations: PlanChangeOperationDto[] = operationsRaw.map((op) => {
      const o = asRecord(op) ?? {}
      return {
        op: (asString(o['op']) ?? 'change_responsibility') as PlanChangeOperationDto['op'],
        stepId: asString(o['stepId']) ?? '',
        target: asString(o['target']),
      }
    })
    return {
      proposalId: asString(raw['proposalId']) ?? '',
      workflowId: asString(raw['workflowId']) ?? workflowId,
      status: asString(raw['status']) ?? 'unknown',
      summary: asString(raw['summary']) ?? '',
      operations,
      revision: asNumber(raw['revision']) ?? 0,
    }
  })
}

function emptyControllerHistory(workflowId: string): ControllerHistoryDto {
  return { workflowId, revision: 0, entries: [] }
}

function toControllerHistory(raw: Record<string, unknown>, workflowId: string): ControllerHistoryDto {
  const cases = asArray(raw['cases'])
  const entries: ControllerHistoryEntryDto[] = cases.map((entry) => {
    const c = asRecord(entry) ?? {}
    const status = asString(c['status']) ?? 'unknown'
    return {
      caseId: asString(c['caseId']) ?? '',
      fromStatus: asString(c['fromStatus']) ?? '',
      toStatus: status,
      at: asString(c['archivedAt']) ?? asString(c['startedAt']) ?? '',
      note: asString(c['compactionReason']),
    }
  })
  return {
    workflowId: asString(raw['workflowId']) ?? workflowId,
    revision: asNumber(raw['revision']) ?? 0,
    entries,
  }
}
