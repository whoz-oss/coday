import { ProjectRepository } from '@coday/repository/project.repository'
import { ProjectLocalConfig } from '@coday/model/project-local-config'
import { ConfigMaskingService } from '@coday/service/config-masking.service'

/**
 * Server-side project management service.
 * Stateless service for managing projects independently of user sessions.
 *
 * This service provides a clean API for project operations without coupling
 * to session state or observables. It delegates all persistence operations
 * to the ProjectRepository interface.
 */
export class ProjectService {
  private readonly maskingService = new ConfigMaskingService()

  constructor(
    private readonly repository: ProjectRepository,
    private readonly defaultProject: string | undefined | null,
    private readonly isForcedMode: boolean
  ) {}

  /**
   * List all available projects
   * In forced mode (--local), only the forced project is returned
   * @returns Array of project objects with name
   */
  listProjects(): Array<{ name: string }> {
    const allProjects = this.repository.listProjects()

    if (this.isForcedMode && this.defaultProject) {
      // --local mode: only show the forced project
      return allProjects.filter((name) => name === this.defaultProject).map((name) => ({ name }))
    }

    // Default or --multi mode: show all projects
    return allProjects.map((name) => ({ name }))
  }

  /**
   * Get the default project (if any)
   */
  getDefaultProject(): string | null {
    return this.defaultProject || null
  }

  /**
   * Check if we're in forced project mode
   */
  getForcedMode(): boolean {
    return this.isForcedMode
  }

  /**
   * Get project details including configuration
   * @param name Project name
   * @returns Project object with name and config, or null if not found
   */
  getProject(name: string): {
    name: string
    config: ProjectLocalConfig
  } | null {
    this.checkAgainstForced(name)
    const config = this.repository.getConfig(name)
    if (!config) {
      return null
    }
    return { name, config }
  }

  /**
   * Check if a project exists
   * @param name Project name
   * @returns true if project exists
   */
  exists(name: string): boolean {
    this.checkAgainstForced(name)
    return this.repository.exists(name)
  }

  /**
   * Create a new project
   * @param name Project name
   * @param projectPath Path to the project directory
   * @throws Error if project already exists or invalid parameters
   */
  createProject(name: string, projectPath: string): void {
    this.checkAgainstForced(name)
    if (!name || !projectPath) {
      throw new Error('Project name and path are required')
    }

    const created = this.repository.createProject(name, projectPath)
    if (!created) {
      throw new Error(`Project '${name}' already exists`)
    }
  }

  /**
   * Update project configuration
   * @param name Project name
   * @param config Configuration to save
   * @throws Error if project doesn't exist
   */
  updateProjectConfig(name: string, config: ProjectLocalConfig): void {
    this.checkAgainstForced(name)
    if (!this.repository.exists(name)) {
      throw new Error(`Project '${name}' does not exist`)
    }

    this.repository.saveConfig(name, config)
  }

  /**
   * Delete a project
   * @param name Project name
   * @throws Error if project doesn't exist
   */
  deleteProject(name: string): void {
    this.checkAgainstForced(name)
    const deleted = this.repository.deleteProject(name)
    if (!deleted) {
      throw new Error(`Project '${name}' does not exist`)
    }
  }

  /**
   * Get masked configuration for client display.
   * Sensitive values (API keys, tokens) are masked.
   * @param name Project name
   * @returns Masked configuration or null if not found
   */
  getProjectConfigForClient(name: string): ProjectLocalConfig | null {
    this.checkAgainstForced(name)
    const config = this.repository.getConfig(name)
    if (!config) {
      return null
    }

    return this.maskingService.maskConfig(config)
  }

  /**
   * Update configuration from client with automatic unmasking.
   * Masked values in incoming config are replaced with original values.
   * @param name Project name
   * @param incomingConfig Configuration from client (may contain masked values)
   * @throws Error if project doesn't exist
   */
  updateProjectConfigFromClient(name: string, incomingConfig: ProjectLocalConfig): void {
    this.checkAgainstForced(name)
    const currentConfig = this.repository.getConfig(name)
    if (!currentConfig) {
      throw new Error(`Project '${name}' does not exist`)
    }

    const unmaskedConfig = this.maskingService.unmaskConfig(incomingConfig, currentConfig)

    this.repository.saveConfig(name, unmaskedConfig)
  }

  private checkAgainstForced(name: string): void {
    if (this.isForcedMode && this.defaultProject && this.defaultProject !== name) {
      throw Error(`Project selection outside of ${this.defaultProject} not allowed`)
    }
  }
}
