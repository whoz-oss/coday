import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Interval-based schedule definition
 */
export interface IntervalSchedule {
  startTimestamp: string // ISO 8601 UTC
  interval: string // '5h', '14d', '1m'
  daysOfWeek?: number[] // 0-6 (0=Sunday, 6=Saturday), optional
  endCondition?: {
    type: 'occurrences' | 'endTimestamp'
    value: number | string // number for occurrences, ISO 8601 for timestamp
  }
}

/**
 * Trigger interface matching backend definition
 */
export interface Trigger {
  id: string
  name: string
  enabled: boolean
  webhookUuid: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  createdBy: string
  createdAt: string
  lastRun?: string
  nextRun?: string | null
  occurrenceCount?: number
}

/**
 * Data for creating a new trigger (without auto-generated fields)
 */
export interface TriggerCreateData {
  name: string
  webhookUuid: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  enabled?: boolean
}

/**
 * Data for updating a trigger (partial updates allowed)
 */
export interface TriggerUpdateData {
  name?: string
  enabled?: boolean
  webhookUuid?: string
  schedule?: IntervalSchedule
  parameters?: Record<string, unknown>
}

/**
 * Angular service for trigger API communication
 *
 * Provides methods to interact with the trigger REST API endpoints.
 * All methods return Observables for reactive programming patterns.
 *
 * Note: All endpoints are scoped by project name.
 */
@Injectable({
  providedIn: 'root',
})
export class TriggerApiService {
  private http = inject(HttpClient)

  /**
   * Build the base URL for trigger endpoints
   */
  private getBaseUrl(projectName: string): string {
    return `/api/projects/${projectName}/triggers`
  }

  /**
   * List all triggers for a project
   */
  listTriggers(projectName: string): Observable<Trigger[]> {
    return this.http.get<Trigger[]>(this.getBaseUrl(projectName))
  }

  /**
   * Get specific trigger by ID
   */
  getTrigger(projectName: string, id: string): Observable<Trigger> {
    return this.http.get<Trigger>(`${this.getBaseUrl(projectName)}/${id}`)
  }

  /**
   * Create new trigger
   */
  createTrigger(projectName: string, data: TriggerCreateData): Observable<Trigger> {
    return this.http.post<Trigger>(this.getBaseUrl(projectName), data)
  }

  /**
   * Update existing trigger
   */
  updateTrigger(projectName: string, id: string, data: TriggerUpdateData): Observable<Trigger> {
    return this.http.put<Trigger>(`${this.getBaseUrl(projectName)}/${id}`, data)
  }

  /**
   * Delete trigger
   */
  deleteTrigger(projectName: string, id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl(projectName)}/${id}`)
  }

  /**
   * Enable trigger
   */
  enableTrigger(projectName: string, id: string): Observable<Trigger> {
    return this.http.post<Trigger>(`${this.getBaseUrl(projectName)}/${id}/enable`, {})
  }

  /**
   * Disable trigger
   */
  disableTrigger(projectName: string, id: string): Observable<Trigger> {
    return this.http.post<Trigger>(`${this.getBaseUrl(projectName)}/${id}/disable`, {})
  }

  /**
   * Run trigger now (manual execution for testing)
   */
  runTriggerNow(projectName: string, id: string): Observable<{ success: boolean; message: string; threadId: string }> {
    return this.http.post<{ success: boolean; message: string; threadId: string }>(
      `${this.getBaseUrl(projectName)}/${id}/run-now`,
      {}
    )
  }
}
