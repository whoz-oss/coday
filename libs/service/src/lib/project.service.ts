import { ProjectLocalConfig } from '@coday/model'
import { ConfigMaskingService } from './config-masking.service'
import * as crypto from 'crypto'
import * as path from 'path'
import { ProjectRepository } from '@coday/repository'

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
   * List all available projects with volatile flag
   * In forced mode (--local), only the forced project is returned
   * @returns Array of project objects with name and volatile flag
   */
  listProjects(): Array<{ name: string; volatile?: boolean }> {
    const allProjects = this.repository.listProjects()

    if (this.isForcedMode && this.defaultProject) {
      // --local mode: only show the forced project
      return allProjects
        .filter((name) => name === this.defaultProject)
        .map((name) => {
          const config = this.repository.getConfig(name)
          return { name, volatile: config?.volatile }
        })
    }

    // Default or --multi mode: show all projects with volatile flag
    return allProjects.map((name) => {
      const config = this.repository.getConfig(name)
      return { name, volatile: config?.volatile }
    })
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
   * If project doesn't exist and we're in default mode, creates a volatile project
   * @param name Project name (can be simple name or full volatile ID)
   * @returns Project object with name and config, or null if not found
   */
  getProject(name: string): {
    name: string
    config: ProjectLocalConfig
  } | null {
    this.checkAgainstForced(name)
    let config = this.repository.getConfig(name)

    // If project exists, return it directly
    if (config) {
      return { name, config }
    }

    // If project doesn't exist and we're in default mode (not forced), try to create volatile
    // This handles the case where a simple name is passed but the project doesn't exist yet
    if (!this.isForcedMode && this.defaultProject) {
      // Check if the requested name matches the default project (could be simple name or volatile ID)
      const cwd = process.cwd()
      const volatileId = ProjectService.generateProjectId(cwd)
      const basename = path.basename(cwd)

      // If the requested name is either the simple basename or the full volatile ID
      if (name === basename || name === volatileId || name === this.defaultProject) {
        const createdId = this.getOrCreateVolatileProject(cwd)
        config = this.repository.getConfig(createdId)
        if (config) {
          return { name: createdId, config }
        }
      }
    }

    // Project not found and not eligible for volatile creation
    return null
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

  /**
   * Get or create a volatile project for the given path
   * Volatile projects are auto-generated when Coday is started in a directory
   * without an existing project configuration
   * @param projectPath Absolute path to the project directory
   * @returns Project ID (basename_hash format)
   */
  private getOrCreateVolatileProject(projectPath: string): string {
    const projectId = ProjectService.generateProjectId(projectPath)

    // Check if project already exists
    if (this.repository.exists(projectId)) {
      return projectId
    }

    // Project doesn't exist, create it
    this.repository.createProject(projectId, projectPath)

    // Enrich config with volatile metadata
    const config = this.repository.getConfig(projectId)
    if (config) {
      config.volatile = true
      config.createdAt = Date.now()
      this.repository.saveConfig(projectId, config)
    }

    return projectId
  }

  /**
   * Generate a stable project ID from an absolute path
   * Format: {basename}_{hash}
   * Example: coday_a1b2c3d4
   * @param absolutePath Absolute path to the project directory
   * @returns Project ID
   */
  static generateProjectId(absolutePath: string): string {
    const basename = path.basename(absolutePath)
    const hash = crypto.createHash('sha256').update(absolutePath).digest('hex').substring(0, 8)

    return `${basename}_${hash}`
  }
}
