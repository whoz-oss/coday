import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Preview server status returned by the API
 */
export interface PreviewStatusResponse {
  status: 'running' | 'stopped'
  port?: number
}

/**
 * A single preview entry as returned by the API
 */
export interface PreviewEntryResponse {
  name: string
  command: string
  url?: string
}

/**
 * Project information returned by the API
 */
export interface ProjectInfo {
  name: string
  volatile?: boolean
}

/**
 * Project list response with context metadata
 */
export interface ProjectListResponse {
  projects: ProjectInfo[]
  defaultProject?: string | null // Project selected by default (PWD in default mode)
  forcedProject?: string | null // Only set if --local mode (restricted)
}

/**
 * Project details with configuration
 */
export interface ProjectDetails {
  name: string
  config: any // ProjectLocalConfig type
}

/**
 * Service for interacting with project REST API
 */
@Injectable({
  providedIn: 'root',
})
export class ProjectApiService {
  private readonly baseUrl = '/api/projects'
  private readonly http = inject(HttpClient)

  /**
   * List all available projects
   */
  listProjects(): Observable<ProjectListResponse> {
    return this.http.get<ProjectListResponse>(this.baseUrl)
  }

  /**
   * Get details of a specific project
   * @param projectName Project name
   */
  getProject(projectName: string): Observable<ProjectDetails> {
    return this.http.get<ProjectDetails>(`${this.baseUrl}/${projectName}`)
  }

  /**
   * Get project configuration (masked)
   * @param projectName Project name
   */
  getProjectConfig(projectName: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/${projectName}/config`)
  }

  /**
   * Update project configuration
   * @param projectName Project name
   * @param config Configuration to update
   */
  updateProjectConfig(projectName: string, config: any): Observable<{ success: boolean; message: string }> {
    return this.http.put<{ success: boolean; message: string }>(`${this.baseUrl}/${projectName}/config`, config)
  }

  /**
   * Create a new project
   * @param name Project name
   * @param path Project path
   */
  createProject(name: string, path: string): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(this.baseUrl, { name, path })
  }

  // ── Git ──────────────────────────────────────────────────────────────────────────────

  getGitBranches(projectName: string): Observable<{ branches: string[] }> {
    return this.http.get<{ branches: string[] }>(`${this.baseUrl}/${projectName}/git/branches`)
  }

  // ── Missions ─────────────────────────────────────────────────────────────────────────

  createMission(
    projectName: string,
    agentName: string,
    task: string,
    mode: 'local' | 'worktree',
    branch?: string,
    issueNumber?: string,
    branchType?: string
  ): Observable<{ threadId: string; projectId: string }> {
    return this.http.post<{ threadId: string; projectId: string }>(`${this.baseUrl}/${projectName}/missions`, {
      agentName,
      task,
      mode,
      branch,
      issueNumber,
      branchType,
    })
  }

  closeMission(projectName: string, threadId: string): Observable<{ success: boolean }> {
    return this.http.delete<{ success: boolean }>(`${this.baseUrl}/${projectName}/missions/${threadId}`)
  }

  /**
   * Delete a project
   * @param projectName Project name
   * @param removeGitWorktree If true, also remove the git worktree from disk (worktree projects only)
   */
  deleteProject(projectName: string, removeGitWorktree?: boolean): Observable<{ success: boolean; message: string }> {
    const params: Record<string, string> = {}
    if (removeGitWorktree) {
      params['removeGitWorktree'] = 'true'
    }
    return this.http.delete<{ success: boolean; message: string }>(
      `${this.baseUrl}/${encodeURIComponent(projectName)}`,
      { params }
    )
  }

  // ── Preview server ─────────────────────────────────────────────────────────────────

  getPreviewEntries(projectName: string): Observable<{ entries: PreviewEntryResponse[] }> {
    return this.http.get<{ entries: PreviewEntryResponse[] }>(`${this.baseUrl}/${projectName}/preview/entries`)
  }

  startPreview(projectName: string, entryName: string): Observable<PreviewStatusResponse> {
    return this.http.post<PreviewStatusResponse>(`${this.baseUrl}/${projectName}/preview/${entryName}/start`, {})
  }

  stopPreview(projectName: string, entryName: string): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.baseUrl}/${projectName}/preview/${entryName}/stop`, {})
  }

  getPreviewStatus(projectName: string, entryName: string): Observable<PreviewStatusResponse> {
    return this.http.get<PreviewStatusResponse>(`${this.baseUrl}/${projectName}/preview/${entryName}/status`)
  }

  getPreviewLogs(projectName: string, entryName: string): Observable<{ logs: string }> {
    return this.http.get<{ logs: string }>(`${this.baseUrl}/${projectName}/preview/${entryName}/logs`)
  }
}
