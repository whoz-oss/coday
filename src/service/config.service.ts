import {existsSync, lstatSync, readdirSync, readFileSync} from "fs"
import os from "os"
import path from "path"
import {mkdirSync, rmSync} from "node:fs"
import {CodayConfig, ProjectLocalConfig} from "../model"
import {readYamlFile} from "./read-yaml-file"
import {writeYamlFile} from "./write-yaml-file"
import {BehaviorSubject, Observable} from "rxjs"

/**
 * Default location for the folder containing local configuration
 */
const DATA_PATH: string = "/.coday"

/**
 * Legacy name of the unique local configuration file
 * It is being deprecated
 */
const LEGACY_CONFIG_FILENAME = "config.json"

/**
 * Name of project configuration file in each project local config folder
 */
const PROJECT_FILENAME = "project.yaml"

type SelectedProject = {
  name: string
  configPath: string
  config: ProjectLocalConfig
} | null

/**
 * Gateway service for config files
 *
 * Handles the legacy config.json file (not for long...)
 * Exposes the :
 *   - selected project (name, path and config) for client services
 *   - user path and config (in future)
 */
export class ConfigService {
  readonly configPath: string
  private selectedProject: SelectedProject = null
  private selectedProjectBehaviorSubject = new BehaviorSubject<SelectedProject>(null)
  selectedProject$: Observable<SelectedProject> = this.selectedProjectBehaviorSubject.asObservable()
  
  /**
   * List of project names, as taken from the folder existing in the config directory
   * Serves as a marker of initialized if not null
   * @private
   */
  private projects: string[] | null = null
  
  constructor() {
    const userInfo = os.userInfo()
    this.configPath = path.join(userInfo.homedir, DATA_PATH)
  }
  
  get projectNames(): string[] {
    this.initConfig()
    return this.projects!!
  }
  
  get project(): ProjectLocalConfig | undefined {
    this.initConfig()
    const projectName = this.selectedProject?.name
    
    // shortcut if no selected project
    if (!projectName) {
      return undefined
    }
    return this.selectedProject?.config
  }
  
  addProject(projectName: string, projectPath: string) {
    this.initConfig()
    
    // create the ./coday/[project] folder
    const projectConfigPath = path.join(this.configPath, projectName)
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
    const projectConfigFolderPath = path.join(this.configPath, name)
    const projectConfigPath = path.join(projectConfigFolderPath, PROJECT_FILENAME)
    const projectConfig = readYamlFile<ProjectLocalConfig>(projectConfigPath)
    
    const projectPath: string | undefined = projectConfig?.path
    if (!projectPath) {
      throw new Error("Invalid selection")
    }
    
    this.updateSelectedProject(projectConfig ? {
      name,
      config: projectConfig,
      configPath: projectConfigFolderPath
    } : null)
    return projectPath
  }
  
  resetProjectSelection(): void {
    this.initConfig()
    this.updateSelectedProject(null)
  }
  
  /**
   * Minimal check and populates projects if not set
   */
  initConfig() {
    if (this.projects === null) {
      // check or create dir to local coday config
      const dir = path.dirname(this.configPath)
      if (!existsSync(dir)) {
        mkdirSync(dir, {recursive: true})
      }
      
      // read legacy config and convert it to new structure
      this.migrateLegacyConfig(this.configPath)
      
      // list projects from the config folder
      const dirs = readdirSync(this.configPath)
      this.projects = dirs.filter(dir => lstatSync(path.join(this.configPath, dir)).isDirectory())
    }
  }
  
  saveProjectConfig(update: Partial<ProjectLocalConfig>): void {
    if (!this.selectedProject?.config || !this.selectedProject.configPath) {
      throw new Error("Could not save project config")
    }
    this.updateSelectedProject({...this.selectedProject, config: {...this.selectedProject.config, ...update}})
    writeYamlFile(
      path.join(this.selectedProject.configPath, PROJECT_FILENAME),
      this.selectedProject.config
    )
  }
  
  private updateSelectedProject(selectedProject: SelectedProject): void {
    this.selectedProject = selectedProject
    this.selectedProjectBehaviorSubject.next(selectedProject)
  }
  
  /**
   * Temporary conversion from unique CodayConfig to many per-project ProjectConfig files
   * @private
   * @param configFolderPath path where is expected the 'config.json' to migrate
   */
  private migrateLegacyConfig(configFolderPath: string): void {
    const legacyConfigPath = path.join(configFolderPath, LEGACY_CONFIG_FILENAME)
    if (!existsSync(legacyConfigPath)) {
      return
    }
    const legacyContent = readFileSync(legacyConfigPath, "utf-8")
    const legacyConfig = JSON.parse(legacyContent) as CodayConfig
    for (const [projectName, projectConfig] of Object.entries(legacyConfig.project)) {
      const projectDir = path.join(this.configPath, projectName)
      if (!existsSync(projectDir)) {
        mkdirSync(projectDir)
      }
      writeYamlFile(
        path.join(projectDir, PROJECT_FILENAME),
        projectConfig
      )
    }
    
    // leap of faith, yolo
    rmSync(legacyConfigPath)
  }
}

export const configService = new ConfigService()
