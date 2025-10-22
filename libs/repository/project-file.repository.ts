import * as path from 'node:path'
import * as os from 'node:os'
import { existsSync, lstatSync, mkdirSync, readdirSync } from 'fs'
import { ProjectRepository, ProjectInfo } from './project.repository'
import { ProjectLocalConfig } from '../model/project-local-config'
import { readYamlFile } from '../service/read-yaml-file'
import { writeYamlFile } from '../service/write-yaml-file'
import { migrateData } from '../utils/data-migration'
import { projectConfigMigrations } from '../service/migration/project-config-migrations'

/**
 * File-based implementation of ProjectRepository.
 * Stores projects as directories with YAML configuration files.
 */
export class ProjectFileRepository implements ProjectRepository {
  private readonly projectsConfigPath: string
  private static readonly PROJECT_FILENAME = 'project.yaml'

  constructor(codayConfigPath: string | undefined) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.projectsConfigPath = path.join(codayConfigPath ?? defaultConfigPath, 'projects')
    // Ensure the directory exists
    mkdirSync(this.projectsConfigPath, { recursive: true })
  }

  listProjects(): string[] {
    const dirs: string[] = readdirSync(this.projectsConfigPath)
    return dirs.filter((dir) => lstatSync(path.join(this.projectsConfigPath, dir)).isDirectory())
  }

  getProjectInfo(name: string): ProjectInfo | null {
    const configPath = path.join(this.projectsConfigPath, name)
    const configFile = path.join(configPath, ProjectFileRepository.PROJECT_FILENAME)

    if (!existsSync(configFile)) {
      return null
    }

    return { name, configPath }
  }

  exists(name: string): boolean {
    return this.getProjectInfo(name) !== null
  }

  getConfig(name: string): ProjectLocalConfig | null {
    const projectInfo = this.getProjectInfo(name)
    if (!projectInfo) {
      return null
    }

    const configFile = path.join(projectInfo.configPath, ProjectFileRepository.PROJECT_FILENAME)
    let rawConfig = readYamlFile(configFile)

    if (!rawConfig) {
      return null
    }

    // Apply migrations
    const migratedConfig = migrateData(rawConfig, projectConfigMigrations)

    // Save migrated config if needed
    if (migratedConfig !== rawConfig) {
      writeYamlFile(configFile, migratedConfig)
    }

    return migratedConfig
  }

  saveConfig(name: string, config: ProjectLocalConfig): void {
    const projectInfo = this.getProjectInfo(name)
    if (!projectInfo) {
      throw new Error(`Project '${name}' does not exist`)
    }

    const configFile = path.join(projectInfo.configPath, ProjectFileRepository.PROJECT_FILENAME)
    writeYamlFile(configFile, config)
  }

  createProject(name: string, projectPath: string): boolean {
    const configPath = path.join(this.projectsConfigPath, name)

    if (existsSync(configPath)) {
      return false
    }

    mkdirSync(configPath, { recursive: true })

    const configFile = path.join(configPath, ProjectFileRepository.PROJECT_FILENAME)
    const defaultConfig: ProjectLocalConfig = {
      version: 1,
      path: projectPath,
      integration: {},
      storage: { type: 'file' },
      agents: [],
    }

    writeYamlFile(configFile, defaultConfig)
    return true
  }

  deleteProject(name: string): boolean {
    const projectInfo = this.getProjectInfo(name)
    if (!projectInfo) {
      return false
    }

    // TODO: Implement safe deletion (maybe just rename with .deleted suffix?)
    // For now, just return false to prevent accidental deletion
    return false
  }
}
