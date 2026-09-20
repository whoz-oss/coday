import { HttpClient, HttpParams } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'
import {
  WorkflowProjectionDetailDto,
  WorkflowProjectionEvent,
  WorkflowProjectionLifecycleDto,
  WorkflowProjectionListDto,
  WorkflowProjectionTimingDto,
  WorkflowHumanInteractionListDto,
  WorkflowHumanReplyDto,
  WorkUnitEnvironmentResponseDto,
} from './factory-workflow-projection.model'

export interface FactoryForgeOracleResult {
  name: string
  status: string
  code?: string
  ownerProjects: string[]
  target: string | null
  buildHosts: string[]
  ownersWithTestTarget: string[]
  ownersWithoutTestTarget: string[]
  exitCode?: number
  durationMs?: number
  commandHash?: string
}

export interface FactoryForgeRun {
  runId: string
  workflow: string
  workItem: { id: string; kind: string }
  roots: { repoRoot?: string }
  startedAt: string
  status: string
  gates: Array<Record<string, unknown> & { gate: string; status: string }>
  stories: Array<{
    runId: string
    ordinal: number
    status: string
    workItem: { id: string; kind: string }
    executions: Array<Record<string, unknown>>
    edits: Array<Record<string, unknown>>
    oracleCampaigns: Array<
      { campaignId: string; status: string; results: FactoryForgeOracleResult[] } & Record<string, unknown>
    >
  }>
}

export interface FactoryRunContext {
  ticketId?: string
  ticketSummary?: string
  roles?: string[]
  caseId?: string
}

export interface FactoryRunPhase {
  name: string
  phaseKind: string
  status: string
  startedAt: string | null
  durationMs: number | null
  facts: Record<string, unknown>
}

export interface FactoryRunDetail extends FactoryRunSummary {
  phases: FactoryRunPhase[]
}

export interface FactoryRunSummary {
  runId: string
  namespaceId?: string
  workflow: string
  status: string
  startedAt: string | null
  endedAt: string | null
  durationMs: number | null
  phaseCount: number
  context?: FactoryRunContext
}

/**
 * Workflows require FACTORY_NAMESPACE_ID, FACTORY_TASK, and at least one agent role.
 * Diagnostics have fewer requirements (see individual workflow docs).
 *
 * fix-loop  : FACTORY_AGENT (single editor)
 * us-loop   : FACTORY_AGENT_ANALYST + FACTORY_AGENT_EDITOR (no FACTORY_AGENT)
 * agentos-smoke : FACTORY_AGENT
 * backend-oracle-check : no agent required
 */
export type FactoryWorkflow = 'fix-loop' | 'us-loop' | 'agentos-smoke' | 'backend-oracle-check'

export interface FactoryLaunchRequest {
  workflow: FactoryWorkflow
  FACTORY_NAMESPACE_ID?: string
  FACTORY_TASK?: string
  FACTORY_AGENT?: string
  FACTORY_AGENT_ANALYST?: string
  FACTORY_AGENT_EDITOR?: string
  FACTORY_DOMAIN?: 'front' | 'back'
  FACTORY_SCOPE?: string
  FACTORY_TICKET?: string
  FACTORY_ROOT?: string
  FACTORY_COMMAND_FRONT?: string
  FACTORY_COMMAND_BACK?: string
  AGENTOS_URL?: string
  FACTORY_USER?: string
}

export interface FactoryLaunchResponse {
  pid: number
  /** runId discovered from the JSONL file; null if the file was not created within 3 seconds. */
  runId: string | null
}

export interface WorkstreamEntry {
  slug: string
  name: string
  status: 'discovery' | 'planning' | string
}

export interface FactoryStopResponse {
  runId: string
  stopping: boolean
}

export interface FactoryAgentConfig {
  name: string
  enabled?: boolean
  subAgents?: string[]
}

/**
 * Response from GET /api/jira/:ticketId — Jira ticket fetched live at display time.
 *
 * PROVENANCE CONTRACT: this content is NOT proof of what the analyst received.
 * Jira tickets are mutable. `fetchedAt` records when this request was made;
 * compare with the phase `startedAt` to understand the temporal gap.
 * Never present this as authoritative input to the analyst.
 */
export interface JiraTicketResponse {
  ticketId: string
  ticketContent: string
  summary: string
  fieldCount: number
  commentCount: number
  commentsIncluded: number
  commentsTruncated: boolean
  /** ISO timestamp of when this fetch occurred (display-time, not run-time). */
  fetchedAt: string
}

/** HTTP boundary for the Factory run endpoints. */
@Injectable({ providedIn: 'root' })
export class FactoryApiService {
  private readonly http = inject(HttpClient)

  listWorkflowProjections(
    namespaceId: string,
    state: 'active' | 'removed' = 'active'
  ): Observable<WorkflowProjectionListDto> {
    return this.http.get<WorkflowProjectionListDto>('/api/factory/workflows', {
      params: new HttpParams().set('namespaceId', namespaceId).set('state', state),
    })
  }

  listRemovedWorkflowProjections(namespaceId: string): Observable<WorkflowProjectionListDto> {
    return this.listWorkflowProjections(namespaceId, 'removed')
  }

  removeWorkflowProjection(
    namespaceId: string,
    workflowId: string,
    actorId?: string
  ): Observable<WorkflowProjectionLifecycleDto> {
    return this.http.delete<WorkflowProjectionLifecycleDto>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}`,
      {
        params: new HttpParams().set('namespaceId', namespaceId),
        ...(actorId ? { body: { actorId } } : {}),
      }
    )
  }

  restoreWorkflowProjection(
    namespaceId: string,
    workflowId: string,
    actorId?: string
  ): Observable<WorkflowProjectionLifecycleDto> {
    return this.http.post<WorkflowProjectionLifecycleDto>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/restore`,
      actorId ? { actorId } : {},
      { params: new HttpParams().set('namespaceId', namespaceId) }
    )
  }

  purgeWorkflowProjection(
    namespaceId: string,
    workflowId: string,
    actorId?: string
  ): Observable<WorkflowProjectionLifecycleDto> {
    return this.http.delete<WorkflowProjectionLifecycleDto>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/purge`,
      {
        params: new HttpParams().set('namespaceId', namespaceId),
        ...(actorId ? { body: { actorId } } : {}),
      }
    )
  }

  getWorkflowEnvironment(
    namespaceId: string,
    workflowId: string,
    caseId: string
  ): Observable<WorkUnitEnvironmentResponseDto> {
    return this.http.get<WorkUnitEnvironmentResponseDto>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/environment`,
      { headers: { 'X-Factory-Namespace-Id': namespaceId, 'X-Factory-Case-Id': caseId } }
    )
  }

  reconcileWorkflowEnvironment(
    namespaceId: string,
    workflowId: string,
    caseId: string
  ): Observable<WorkUnitEnvironmentResponseDto> {
    return this.http.post<WorkUnitEnvironmentResponseDto>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/environment/reconcile`,
      {},
      { headers: { 'X-Factory-Namespace-Id': namespaceId, 'X-Factory-Case-Id': caseId } }
    )
  }

  getWorkflowProjection(namespaceId: string, workflowId: string): Observable<WorkflowProjectionDetailDto> {
    return this.http.get<WorkflowProjectionDetailDto>(`/api/factory/workflows/${encodeURIComponent(workflowId)}`, {
      params: new HttpParams().set('namespaceId', namespaceId),
    })
  }

  getWorkflowProjectionTiming(namespaceId: string, workflowId: string): Observable<WorkflowProjectionTimingDto> {
    return this.http.get<WorkflowProjectionTimingDto>(
      `/api/factory/workflows/${encodeURIComponent(workflowId)}/timing`,
      { params: new HttpParams().set('namespaceId', namespaceId) }
    )
  }

  workflowProjectionStreamUrl(namespaceId: string): string {
    return `/api/factory/workflows/stream?namespaceId=${encodeURIComponent(namespaceId)}`
  }

  streamWorkflowProjectionUpdates(namespaceId: string): Observable<WorkflowProjectionEvent> {
    return new Observable((subscriber) => {
      const source = new EventSource(this.workflowProjectionStreamUrl(namespaceId))
      source.onopen = () => subscriber.next({ type: 'open', namespaceId })
      const listen = (name: string, type: Exclude<WorkflowProjectionEvent['type'], 'open'>) => {
        source.addEventListener(name, (event: MessageEvent<string>) => {
          try {
            subscriber.next({ type, ...JSON.parse(event.data) } as WorkflowProjectionEvent)
          } catch {
            subscriber.error(new Error('Invalid workflow projection event'))
          }
        })
      }
      listen('workflow-projection-updated', 'updated')
      listen('workflow-projection-removed', 'removed')
      listen('workflow-projection-restored', 'restored')
      listen('workflow-projection-purged', 'purged')
      source.onerror = () => subscriber.error(new Error('Workflow projection stream disconnected'))
      return () => source.close()
    })
  }

  listRuns(namespaceId: string): Observable<FactoryRunSummary[]> {
    return this.http.get<FactoryRunSummary[]>('/api/factory/runs', {
      params: new HttpParams().set('namespaceId', namespaceId),
    })
  }

  /** Global read-only projections of Epic/Story Forge runs for the given namespace; JSONL is parsed server-side only. */
  listForgeRuns(namespaceId: string): Observable<FactoryForgeRun[]> {
    return this.http.get<FactoryForgeRun[]>('/api/factory/forge/runs', {
      params: new HttpParams().set('namespaceId', namespaceId),
    })
  }

  /**
   * Launch a new factory run. The server waits up to 3 seconds for the runId
   * to be discovered from the JSONL file before responding.
   */
  launchRun(request: FactoryLaunchRequest): Observable<FactoryLaunchResponse> {
    return this.http.post<FactoryLaunchResponse>('/api/factory/runs', request)
  }

  streamRun(runId: string): Observable<MessageEvent> {
    return new Observable((subscriber) => {
      const source = new EventSource(`/api/factory/runs/${encodeURIComponent(runId)}/stream`)
      source.onmessage = (event) => subscriber.next(event)
      source.onerror = () => subscriber.error(new Error('Factory run stream disconnected'))
      return () => source.close()
    })
  }

  getRun(runId: string): Observable<FactoryRunDetail> {
    return this.http.get<FactoryRunDetail>(`/api/factory/runs/${encodeURIComponent(runId)}`)
  }

  /**
   * Sends a stop signal (SIGTERM) to the tracked child process for the given run.
   * Returns 202 on success, 409 if already stopping, 410 if already finished, 404 if unknown.
   * Does NOT finalize the registry — the child's shutdown handler owns that.
   */
  stopRun(runId: string): Observable<FactoryStopResponse> {
    return this.http.post<FactoryStopResponse>(`/api/factory/runs/${encodeURIComponent(runId)}/stop`, {})
  }

  /**
   * Fetch a Jira ticket's current content from the dashboard server.
   *
   * This is a live fetch — the ticket may have changed since the run.
   * Returns 501 when Jira credentials are not configured on the server.
   * The caller is responsible for displaying the provenance warning.
   */
  getJiraTicket(ticketId: string): Observable<JiraTicketResponse> {
    // Path must be under /api/factory/ so the Angular dev-server proxy
    // (proxy.conf.json) forwards it to the factory dashboard on port 3141.
    // The raw /api/jira/ prefix has no proxy rule and falls through to the
    // SPA index — see proxy.conf.json and factory/dashboard/server.mjs.
    return this.http.get<JiraTicketResponse>(`/api/factory/jira/${encodeURIComponent(ticketId)}`)
  }

  listWorkstreams(namespaceId: string): Observable<WorkstreamEntry[]> {
    return this.http.get<WorkstreamEntry[]>('/api/factory/workstreams', {
      params: new HttpParams().set('namespaceId', namespaceId),
    })
  }

  /**
   * Soumet une décision d'approbation humaine sur la gate G1 d'un run Forge.
   * POST /api/factory/forge/runs/:runId/gates/G1/decision
   */
  approveG1(runId: string, evidenceSetHash: string, namespaceId: string): Observable<unknown> {
    return this.http.post(
      `/api/factory/forge/runs/${encodeURIComponent(runId)}/gates/G1/decision?namespaceId=${encodeURIComponent(namespaceId)}`,
      {
        gate: 'G1',
        attempt: 1,
        policyVersion: 'forge-g1-human-v1',
        evidenceSetHash,
        outcome: 'approved',
        reasonCode: 'intent_confirmed',
      },
      {
        headers: {
          'X-Factory-Actor-Id': 'benjamin.valdes',
          'X-Factory-Authority-Id': 'product-owner',
        },
      }
    )
  }
}
