import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

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
  promptId: string
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
  promptId: string
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

  private getBaseUrl(projectName: string): string {
    return `/api/projects/${projectName}/schedulers`
  }

  /**
   * List all schedulers for a project (with access control)
   */
  listSchedulers(projectName: string): Observable<SchedulerInfo[]> {
    return this.http.get<SchedulerInfo[]>(this.getBaseUrl(projectName))
  }

  /**
   * Get a specific scheduler by ID
   */
  getScheduler(projectName: string, id: string): Observable<Scheduler> {
    return this.http.get<Scheduler>(`${this.getBaseUrl(projectName)}/${id}`)
  }

  /**
   * Create a new scheduler
   */
  createScheduler(
    projectName: string,
    scheduler: {
      name: string
      promptId: string
      schedule: IntervalSchedule
      parameters?: Record<string, unknown>
      enabled?: boolean
    }
  ): Observable<Scheduler> {
    return this.http.post<Scheduler>(this.getBaseUrl(projectName), scheduler)
  }

  /**
   * Update an existing scheduler
   */
  updateScheduler(
    projectName: string,
    id: string,
    updates: {
      name?: string
      enabled?: boolean
      promptId?: string
      schedule?: IntervalSchedule
      parameters?: Record<string, unknown>
    }
  ): Observable<Scheduler> {
    return this.http.put<Scheduler>(`${this.getBaseUrl(projectName)}/${id}`, updates)
  }

  /**
   * Delete a scheduler
   */
  deleteScheduler(projectName: string, id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl(projectName)}/${id}`)
  }

  /**
   * Enable a scheduler
   */
  enableScheduler(projectName: string, id: string): Observable<Scheduler> {
    return this.http.post<Scheduler>(`${this.getBaseUrl(projectName)}/${id}/enable`, {})
  }

  /**
   * Disable a scheduler
   */
  disableScheduler(projectName: string, id: string): Observable<Scheduler> {
    return this.http.post<Scheduler>(`${this.getBaseUrl(projectName)}/${id}/disable`, {})
  }

  /**
   * Manually execute a scheduler now (for testing)
   */
  runSchedulerNow(
    projectName: string,
    id: string
  ): Observable<{ success: boolean; message: string; threadId: string }> {
    return this.http.post<{ success: boolean; message: string; threadId: string }>(
      `${this.getBaseUrl(projectName)}/${id}/run-now`,
      {}
    )
  }
}
