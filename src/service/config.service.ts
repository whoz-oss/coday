import {existsSync, lstatSync, readdirSync, readFileSync} from "fs"
import os from "os"
import path from "path"
import {mkdirSync, rmSync} from "node:fs"
import {CodayConfig, ProjectConfig} from "../model"
import {memoryService} from "./memory-service"
import {readYamlFile} from "./read-yaml-file"
import {writeYamlFile} from "./write-yaml-file"

const DATA_PATH: string = "/.coday"
const LEGACY_CONFIG_FILENAME = "config.json"
const PROJECT_FILENAME = "project.yaml"

type SelectedProject = {
  name: string
  configPath: string
  config: ProjectConfig
}

/**
 * Gateway service for config files
 *
 * Handles the legacy config.json file (not for long...)
 * Exposes the :
 *   - selected project path and config for client services
 *   - user path and config (in future) for memory service for now
 */
export class ConfigService {
  private readonly legacyConfigPath: string
  private readonly codayConfigPath: string
  private projectSelection: SelectedProject | null = null
  
  /**
   * List of project names, as taken from the folder existing in the config directory
   * Serves as a marker of initialized if not null
   * @private
   */
  private projects: string[] | null = null
  
  constructor() {
    const userInfo = os.userInfo()
    this.codayConfigPath = path.join(userInfo.homedir, DATA_PATH)
    this.legacyConfigPath = `${this.codayConfigPath}/${LEGACY_CONFIG_FILENAME}`
  }
  
  get projectNames(): string[] {
    this.initConfig()
    return this.projects!!
  }
  
  get project(): ProjectConfig | undefined {
    this.initConfig()
    const projectName = this.projectSelection?.name
    
    // shortcut if no selected project
    if (!projectName) {
      return undefined
    }
    return this.projectSelection?.config
  }
  
  addProject(projectName: string, projectPath: string) {
    this.initConfig()
    
    // create the ./coday/[project] folder
    const projectConfigPath = path.join(this.codayConfigPath, projectName)
    if (!existsSync(projectConfigPath)) {
      mkdirSync(projectConfigPath)
      const projectConfigFile = path.join(projectConfigPath, PROJECT_FILENAME)
      writeYamlFile(projectConfigFile, {path: projectPath, integration: {}})
    }
    this.projects?.push(projectName)
  }
  
  selectProject(name: string): string {
    this.initConfig()
    
    // check the project folder and config file exists
    const projectConfigFolderPath = path.join(this.codayConfigPath, name)
    const projectConfigPath = path.join(projectConfigFolderPath, PROJECT_FILENAME)
    const projectConfig = readYamlFile<ProjectConfig>(projectConfigPath)
    
    const projectPath: string | undefined = projectConfig?.path
    if (!projectPath) {
      throw new Error("Invalid selection")
    }
    
    memoryService.setPaths(this.codayConfigPath, projectConfigFolderPath)
    
    this.projectSelection = projectConfig ? {
      name,
      config: projectConfig,
      configPath: projectConfigPath
    } : null
    return projectPath
  }
  
  resetProjectSelection(): void {
    this.initConfig()
    this.projectSelection = null
  }
  
  /**
   * Should do the minimum regarding coday config: read the user config to come
   */
  initConfig() {
    if (this.projects === null) {
      // check or create dir to local coday config
      const dir = path.dirname(this.codayConfigPath)
      if (!existsSync(dir)) {
        mkdirSync(dir, {recursive: true})
      }
      
      // read legacy config and convert it to new structure
      const legacyConfigPath = path.join(this.codayConfigPath, LEGACY_CONFIG_FILENAME)
      this.convertToNewStructure(legacyConfigPath)
      
      // list projects from the config folder
      const dirs = readdirSync(this.codayConfigPath)
      this.projects = dirs.filter(dir => lstatSync(path.join(this.codayConfigPath, dir)).isDirectory())
    }
  }
  
  saveProjectConfig(): void {
    if (!this.projectSelection?.config || !this.projectSelection.configPath) {
      throw new Error("Could not save project config")
    }
    writeYamlFile(
      path.join(this.projectSelection.configPath, PROJECT_FILENAME),
      this.projectSelection.config
    )
  }
  
  /**
   * Temporary conversion from unique CodayConfig to many per-project ProjectConfig files
   * @private
   * @param legacyConfigPath
   */
  private convertToNewStructure(legacyConfigPath: string): void {
    if (!existsSync(legacyConfigPath)) {
      return
    }
    const legacyConfig = JSON.parse(readFileSync(legacyConfigPath, "utf-8")) as CodayConfig
    for (const [projectName, projectConfig] of Object.entries(legacyConfig.project)) {
      const projectDir = path.join(this.codayConfigPath, projectName)
      if (!existsSync(projectDir)) {
        mkdirSync(projectDir)
      }
      writeYamlFile(
        path.join(projectDir, PROJECT_FILENAME),
        projectConfig
      )
    }
    rmSync(legacyConfigPath)
  }
}

export const configService = new ConfigService()
