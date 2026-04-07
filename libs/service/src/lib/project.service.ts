import { ProjectLocalConfig } from '@coday/model'
import { ConfigMaskingService } from './config-masking.service'
import * as crypto from 'crypto'
import * as fsp from 'node:fs/promises'
import * as fs from 'node:fs'
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
   * List all available projects with volatile flag.
   * In forced mode (--local), only the forced project is returned.
   * Projects whose configured path no longer exists on disk are filtered out
   * (e.g. worktrees that were deleted manually).
   */
  listProjects(): Array<{ name: string; volatile?: boolean }> {
    const allProjects = this.repository.listProjects()

    if (this.isForcedMode && this.defaultProject) {
      return allProjects
        .filter((name) => name === this.defaultProject)
        .map((name) => {
          const config = this.repository.getConfig(name)
          return { name, volatile: config?.volatile }
        })
    }

    return allProjects
      .map((name) => {
        const config = this.repository.getConfig(name)
        return { name, volatile: config?.volatile, projectPath: config?.path }
      })
      .filter(({ projectPath }) => {
        if (!projectPath) return true
        try {
          return fs.existsSync(projectPath)
        } catch {
          return true
        }
      })
      .map(({ name, volatile }) => ({ name, volatile }))
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
   */
  getProject(name: string): {
    name: string
    config: ProjectLocalConfig
  } | null {
    this.checkAgainstForced(name)
    let config = this.repository.getConfig(name)

    if (config) {
      return { name, config }
    }

    if (!this.isForcedMode && this.defaultProject) {
      const cwd = process.cwd()
      const volatileId = ProjectService.generateProjectId(cwd)
      const basename = path.basename(cwd)

      if (name === basename || name === volatileId || name === this.defaultProject) {
        console.log(`[PROJECT_SERVICE] Creating volatile project for '${name}' at ${cwd}`)
        const createdId = this.getOrCreateVolatileProject(cwd)
        config = this.repository.getConfig(createdId)
        if (config) {
          return { name: createdId, config }
        }
      }
    }

    return null
  }

  exists(name: string): boolean {
    this.checkAgainstForced(name)
    return this.repository.exists(name)
  }

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

  updateProjectConfig(name: string, config: ProjectLocalConfig): void {
    this.checkAgainstForced(name)
    if (!this.repository.exists(name)) {
      throw new Error(`Project '${name}' does not exist`)
    }

    this.repository.saveConfig(name, config)
  }

  deleteProject(name: string): void {
    this.checkAgainstForced(name)
    const deleted = this.repository.deleteProject(name)
    if (!deleted) {
      throw new Error(`Project '${name}' does not exist`)
    }
  }

  getProjectConfigForClient(name: string): ProjectLocalConfig | null {
    this.checkAgainstForced(name)
    const config = this.repository.getConfig(name)
    if (!config) {
      return null
    }

    return this.maskingService.maskConfig(config)
  }

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

  private getOrCreateVolatileProject(projectPath: string): string {
    const projectId = ProjectService.generateProjectId(projectPath)

    if (this.repository.exists(projectId)) {
      return projectId
    }

    this.repository.createProject(projectId, projectPath)

    const config = this.repository.getConfig(projectId)
    if (config) {
      config.volatile = true
      config.createdAt = Date.now()
      this.repository.saveConfig(projectId, config)
    }

    return projectId
  }

  /**
   * Register a worktree as a Coday project, copying the parent project's configuration
   * and overriding only the path. Symlinks shared config artifacts from the parent.
   */
  async registerWorktreeProject(projectName: string, worktreePath: string, parentProjectName: string): Promise<void> {
    const parentConfig = this.repository.getConfig(parentProjectName)
    const worktreeConfig: ProjectLocalConfig = parentConfig
      ? { ...parentConfig, path: worktreePath, volatile: undefined, createdAt: undefined }
      : { version: 1, path: worktreePath, integration: {}, storage: { type: 'file' }, agents: [] }

    this.repository.createProject(projectName, worktreePath)
    this.repository.saveConfig(projectName, worktreeConfig)

    const projectInfo = this.repository.getProjectInfo(parentProjectName)
    if (projectInfo) {
      const worktreeInfo = this.repository.getProjectInfo(projectName)
      if (worktreeInfo) {
        for (const dir of ['agents', 'prompts', 'schedulers', 'memories']) {
          const parentDir = path.join(projectInfo.configPath, dir)
          const targetLink = path.join(worktreeInfo.configPath, dir)
          try {
            await fsp.access(parentDir)
            await fsp.symlink(parentDir, targetLink)
          } catch {
            // parent dir doesn't exist or symlink already exists
          }
        }
      }
    }
  }

  /**
   * Unregister a worktree project: migrates its threads to the parent project,
   * then removes the worktree config directory.
   */
  async unregisterWorktreeProject(projectName: string): Promise<void> {
    const projectInfo = this.repository.getProjectInfo(projectName)
    if (!projectInfo) return

    const separatorIndex = projectName.lastIndexOf('__')
    if (separatorIndex !== -1) {
      const parentProjectName = projectName.substring(0, separatorIndex)
      const parentInfo = this.repository.getProjectInfo(parentProjectName)
      if (parentInfo) {
        await this.migrateThreadsToParent(projectInfo.configPath, parentInfo.configPath)
      }
    }

    await fsp.rm(projectInfo.configPath, { recursive: true, force: true })
  }

  private async migrateThreadsToParent(worktreeConfigPath: string, parentConfigPath: string): Promise<void> {
    const worktreeThreadsDir = path.join(worktreeConfigPath, 'threads')
    const parentThreadsDir = path.join(parentConfigPath, 'threads')

    try {
      await fsp.access(worktreeThreadsDir)
    } catch {
      return
    }

    await fsp.mkdir(parentThreadsDir, { recursive: true })

    const entries = await fsp.readdir(worktreeThreadsDir)
    for (const entry of entries) {
      const src = path.join(worktreeThreadsDir, entry)
      const dest = path.join(parentThreadsDir, entry)
      try {
        await fsp.access(dest)
      } catch {
        await fsp.rename(src, dest)
      }
    }
  }

  static generateProjectId(absolutePath: string): string {
    const basename = path.basename(absolutePath)
    const hash = crypto.createHash('sha256').update(absolutePath).digest('hex').substring(0, 8)
    return `${basename}_${hash}`
  }
}
