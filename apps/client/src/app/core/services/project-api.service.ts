import { Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Project information returned by the API
 */
export interface ProjectInfo {
  name: string
}

/**
 * Project list response
 */
export interface ProjectListResponse {
  projects: ProjectInfo[]
  forcedProject?: string | null
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

  constructor(private readonly http: HttpClient) {}

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
}
