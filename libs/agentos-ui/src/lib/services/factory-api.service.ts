import { HttpClient, HttpParams } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'

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

export interface FactoryStopResponse {
  runId: string
  stopping: boolean
}

export interface FactoryAgentConfig {
  name: string
  enabled?: boolean
  subAgents?: string[]
}

/** HTTP boundary for the Factory run endpoints. */
@Injectable({ providedIn: 'root' })
export class FactoryApiService {
  private readonly http = inject(HttpClient)

  listRuns(namespaceId: string): Observable<FactoryRunSummary[]> {
    return this.http.get<FactoryRunSummary[]>('/api/factory/runs', {
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
}
