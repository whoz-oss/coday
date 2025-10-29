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
    const result = this.getProjectInfo(name) !== null
    console.log(`[PROJECT-REPO] exists('${name}'): ${result}`)
    return result
  }

  getConfig(name: string): ProjectLocalConfig | null {
    console.log(`[PROJECT-REPO] getConfig('${name}') called`)
    const projectInfo = this.getProjectInfo(name)
    if (!projectInfo) {
      console.log(`[PROJECT-REPO] getConfig('${name}'): project info not found`)
      return null
    }

    const configFile = path.join(projectInfo.configPath, ProjectFileRepository.PROJECT_FILENAME)
    console.log(`[PROJECT-REPO] getConfig('${name}'): reading from ${configFile}`)
    let rawConfig = readYamlFile(configFile)

    if (!rawConfig) {
      console.log(`[PROJECT-REPO] getConfig('${name}'): failed to read config file`)
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
    console.log(`[PROJECT-REPO] createProject('${name}', '${projectPath}') called`)
    const configPath = path.join(this.projectsConfigPath, name)
    console.log(`[PROJECT-REPO] createProject: configPath = ${configPath}`)

    if (existsSync(configPath)) {
      console.log(`[PROJECT-REPO] createProject('${name}'): directory already exists, returning false`)
      return false
    }

    console.log(`[PROJECT-REPO] createProject('${name}'): creating directory`)
    mkdirSync(configPath, { recursive: true })

    const configFile = path.join(configPath, ProjectFileRepository.PROJECT_FILENAME)
    const defaultConfig: ProjectLocalConfig = {
      version: 1,
      path: projectPath,
      integration: {},
      storage: { type: 'file' },
      agents: [],
    }

    console.log(`[PROJECT-REPO] createProject('${name}'): writing config to ${configFile}`)
    writeYamlFile(configFile, defaultConfig)
    console.log(`[PROJECT-REPO] createProject('${name}'): success`)
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
