import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ProjectStateService } from './project-state.service'

/**
 * IntervalSchedule model matching backend
 */
export interface IntervalSchedule {
  startTimestamp: string
  interval: string
  daysOfWeek?: number[]
  endCondition?: {
    type: 'occurrences' | 'endTimestamp'
    value: number | string
  }
}

/**
 * Scheduler model matching backend
 */
export interface Scheduler {
  id: string
  name: string
  enabled: boolean
  promptId?: string
  agentName?: string
  instruction?: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  createdBy: string
  createdAt: string
  lastRun?: string
  nextRun?: string | null
  occurrenceCount?: number
}

export interface SchedulerInfo {
  id: string
  name: string
  enabled: boolean
  promptId?: string
  agentName?: string
  instruction?: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  lastRun?: string
  nextRun?: string | null
  occurrenceCount?: number
  createdBy: string
}

/**
 * SchedulerApiService - HTTP client for scheduler management
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/schedulers
 * - GET    /api/projects/:projectName/schedulers/:id
 * - POST   /api/projects/:projectName/schedulers
 * - PUT    /api/projects/:projectName/schedulers/:id
 * - DELETE /api/projects/:projectName/schedulers/:id
 * - POST   /api/projects/:projectName/schedulers/:id/enable
 * - POST   /api/projects/:projectName/schedulers/:id/disable
 * - POST   /api/projects/:projectName/schedulers/:id/run-now
 */
@Injectable({
  providedIn: 'root',
})
export class SchedulerApiService {
  private http = inject(HttpClient)
  private projectState = inject(ProjectStateService)

  private getBaseUrl(): string {
    return `/api/projects/${this.projectState.getSelectedProjectIdOrThrow()}/schedulers`
  }

  /**
   * List all schedulers for the current project (with access control)
   */
  listSchedulers(): Observable<SchedulerInfo[]> {
    return this.http.get<SchedulerInfo[]>(this.getBaseUrl())
  }

  /**
   * Get a specific scheduler by ID
   */
  getScheduler(id: string): Observable<Scheduler> {
    return this.http.get<Scheduler>(`${this.getBaseUrl()}/${id}`)
  }

  /**
   * Create a new scheduler
   */
  createScheduler(scheduler: {
    name: string
    promptId?: string
    agentName?: string
    instruction?: string
    schedule: IntervalSchedule
    parameters?: Record<string, unknown>
    enabled?: boolean
  }): Observable<Scheduler> {
    return this.http.post<Scheduler>(this.getBaseUrl(), scheduler)
  }

  /**
   * List schedulers for a specific project (without requiring ProjectStateService)
   */
  listSchedulersForProject(projectName: string): Observable<SchedulerInfo[]> {
    return this.http.get<SchedulerInfo[]>(`/api/projects/${projectName}/schedulers`)
  }

  /**
   * Enable a scheduler for a specific project
   */
  enableSchedulerForProject(projectName: string, id: string): Observable<Scheduler> {
    return this.http.post<Scheduler>(`/api/projects/${projectName}/schedulers/${id}/enable`, {})
  }

  /**
   * Disable a scheduler for a specific project
   */
  disableSchedulerForProject(projectName: string, id: string): Observable<Scheduler> {
    return this.http.post<Scheduler>(`/api/projects/${projectName}/schedulers/${id}/disable`, {})
  }

  /**
   * Run a scheduler now for a specific project
   */
  runSchedulerNowForProject(
    projectName: string,
    id: string
  ): Observable<{ success: boolean; message: string; threadId: string }> {
    return this.http.post<{ success: boolean; message: string; threadId: string }>(
      `/api/projects/${projectName}/schedulers/${id}/run-now`,
      {}
    )
  }

  /**
   * Delete a scheduler for a specific project
   */
  deleteSchedulerForProject(projectName: string, id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`/api/projects/${projectName}/schedulers/${id}`)
  }

  /**
   * Update a scheduler for a specific project
   */
  updateSchedulerForProject(
    projectName: string,
    id: string,
    updates: {
      name?: string
      enabled?: boolean
      agentName?: string
      instruction?: string
      schedule?: IntervalSchedule
    }
  ): Observable<Scheduler> {
    return this.http.put<Scheduler>(`/api/projects/${projectName}/schedulers/${id}`, updates)
  }

  /**
   * Create a scheduler for a specific project (used by quick scheduler from home)
   */
  createSchedulerForProject(
    projectName: string,
    scheduler: {
      name: string
      agentName: string
      instruction: string
      schedule: IntervalSchedule
      enabled?: boolean
    }
  ): Observable<Scheduler> {
    return this.http.post<Scheduler>(`/api/projects/${projectName}/schedulers`, scheduler)
  }

  /**
   * Update an existing scheduler
   */
  updateScheduler(
    id: string,
    updates: {
      name?: string
      enabled?: boolean
      promptId?: string
      schedule?: IntervalSchedule
      parameters?: Record<string, unknown>
    }
  ): Observable<Scheduler> {
    return this.http.put<Scheduler>(`${this.getBaseUrl()}/${id}`, updates)
  }

  /**
   * Delete a scheduler
   */
  deleteScheduler(id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl()}/${id}`)
  }

  /**
   * Enable a scheduler
   */
  enableScheduler(id: string): Observable<Scheduler> {
    return this.http.post<Scheduler>(`${this.getBaseUrl()}/${id}/enable`, {})
  }

  /**
   * Disable a scheduler
   */
  disableScheduler(id: string): Observable<Scheduler> {
    return this.http.post<Scheduler>(`${this.getBaseUrl()}/${id}/disable`, {})
  }

  /**
   * Manually execute a scheduler now (for testing)
   */
  runSchedulerNow(id: string): Observable<{ success: boolean; message: string; threadId: string }> {
    return this.http.post<{ success: boolean; message: string; threadId: string }>(
      `${this.getBaseUrl()}/${id}/run-now`,
      {}
    )
  }
}
