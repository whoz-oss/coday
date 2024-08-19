import {existsSync, readFileSync, writeFileSync} from "fs"
import os from "os"
import path from "path"
import {mkdirSync} from "node:fs"
import {CodayConfig, ProjectConfig} from "../model"

const DATA_PATH: string = "/.coday"
const CONFIG_FILENAME = "config.json"

export class ConfigService {
  private config: CodayConfig | null = null
  private readonly configPath: string
  
  constructor() {
    const userInfo = os.userInfo()
    const codayPath = path.join(userInfo.homedir, DATA_PATH)
    this.configPath = `${codayPath}/${CONFIG_FILENAME}`
  }
  
  get lastProject() {
    this.initConfig()
    return this.config!.currentProject
  }
  
  get projectNames() {
    this.initConfig()
    return Object.keys(this.config!.project)
  }
  
  addProject(projectName: string, projectPath: string) {
    this.initConfig()
    this.config!.project[projectName] = {path: projectPath, integration: {}}
    this.saveConfigFile()
  }
  
  selectProject(name: string): string {
    this.initConfig()
    const projectPath: string | undefined = this.config!.project[name]?.path
    if (!projectPath) {
      throw new Error("Invalid selection")
    }
    this.config!.currentProject = name
    this.saveConfigFile()
    return projectPath
  }
  
  resetProjectSelection(): void {
    this.initConfig()
    this.config!.currentProject = undefined
    this.saveConfigFile()
  }
  
  initConfig() {
    if (!this.config) {
      const dir = path.dirname(this.configPath)
      if (!existsSync(dir)) {
        mkdirSync(dir, {recursive: true})
      }
      if (!existsSync(this.configPath)) {
        this.config = {
          project: {},
        }
        this.saveConfigFile()
      } else {
        this.config = JSON.parse(readFileSync(this.configPath, "utf-8")) as CodayConfig
      }
    }
  }
  
  saveConfigFile(): void {
    const json = JSON.stringify(this.config, null, 2)
    writeFileSync(this.configPath, json)
  }
  
  getProject(): ProjectConfig | undefined {
    this.initConfig()
    const projectName = this.config!.currentProject
    
    // shortcut if no selected project
    if (!projectName) {
      return undefined
    }
    return this.config!.project[projectName]
  }
}

export const configService = new ConfigService()
