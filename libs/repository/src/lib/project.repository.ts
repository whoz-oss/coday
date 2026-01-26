import { ProjectLocalConfig } from '@coday/model'

/**
 * Project information including metadata
 */
export interface ProjectInfo {
  name: string
  configPath: string
}

/**
 * Repository interface for project persistence operations.
 * Implementations can be file-based, database-based, or remote API-based.
 */
export interface ProjectRepository {
  /**
   * List all available projects
   * @returns Array of project names
   */
  listProjects(): string[]

  /**
   * Get full project information including config path
   * @param name Project name
   * @returns ProjectInfo or null if not found
   */
  getProjectInfo(name: string): ProjectInfo | null

  /**
   * Check if a project exists
   * @param name Project name
   */
  exists(name: string): boolean

  /**
   * Get project configuration
   * @param name Project name
   * @returns ProjectLocalConfig or null if not found
   */
  getConfig(name: string): ProjectLocalConfig | null

  /**
   * Save project configuration
   * @param name Project name
   * @param config Configuration to save
   * @throws Error if project does not exist
   */
  saveConfig(name: string, config: ProjectLocalConfig): void

  /**
   * Create a new project
   * @param name Project name
   * @param projectPath Path to the project directory
   * @returns true if created successfully, false if already exists
   */
  createProject(name: string, projectPath: string): boolean

  /**
   * Delete a project
   * @param name Project name
   * @returns true if deleted successfully, false if not found
   */
  deleteProject(name: string): boolean
}
