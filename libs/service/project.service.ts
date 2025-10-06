import * as path from 'node:path'
import * as os from 'node:os'
import { existsSync, lstatSync, mkdirSync, readdirSync } from 'fs'
import { BehaviorSubject, Observable } from 'rxjs'
import { Interactor, ProjectLocalConfig, SelectedProject } from '../model'
import { writeYamlFile } from './write-yaml-file'
import { readYamlFile } from './read-yaml-file'
import { ProjectSelectedEvent } from '@coday/coday-events'
import { migrateData } from '../utils/data-migration'
import { projectConfigMigrations } from './migration/project-config-migrations'

const PROJECTS = 'projects'
const PROJECT_FILENAME = 'project.yaml'

export class ProjectService {
  private projectsConfigPath: string

  /**
   * List of project names, as taken from the folder existing in the config directory
   * Serves as a marker of initialized if not null
   * @public
   */
  projects: string[]

  private selectedProjectBehaviorSubject = new BehaviorSubject<SelectedProject>(null)
  selectedProject$: Observable<SelectedProject> = this.selectedProjectBehaviorSubject.asObservable()

  constructor(
    private interactor: Interactor,
    codayConfigPath: string | undefined
  ) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.projectsConfigPath = path.join(codayConfigPath ?? defaultConfigPath, PROJECTS)
    // Ensure the user's directory exists
    mkdirSync(this.projectsConfigPath, { recursive: true })
    const dirs: string[] = readdirSync(this.projectsConfigPath)
    this.projects = dirs.filter((dir) => lstatSync(path.join(this.projectsConfigPath, dir)).isDirectory())
  }

  addProject(projectName: string, projectPath: string): void {
    const projectConfigPath = path.join(this.projectsConfigPath, projectName)
    if (existsSync(projectConfigPath)) {
      this.interactor.error(`Project already exists for name '${projectName}' 🛑.`)
      return
    }
    mkdirSync(projectConfigPath)
    const projectConfigFile = path.join(projectConfigPath, PROJECT_FILENAME)
    const defaultProjectConfig: ProjectLocalConfig = {
      version: 1,
      path: projectPath,
      integration: {},
      storage: { type: 'file' },
      agents: [],
    }
    writeYamlFile(projectConfigFile, defaultProjectConfig)
    this.projects?.push(projectName)
    this.selectProject(projectName)
  }

  selectProject(name: string): void {
    const projectConfigPath = path.join(this.projectsConfigPath, name)
    const projectConfigFile = path.join(projectConfigPath, PROJECT_FILENAME)
    if (!existsSync(projectConfigFile)) {
      this.interactor.error(`Could not select project '${name}', config file not found 🤷.`)
      return
    }
    let rawProjectConfig = readYamlFile(projectConfigFile)
    if (!rawProjectConfig) {
      this.interactor.error(`Nothing in project configuration 🤨.`)
      return
    }

    // Apply migrations
    const migrationResult = migrateData(rawProjectConfig, projectConfigMigrations)

    // Save the migrated config if needed
    if (migrationResult !== rawProjectConfig) {
      writeYamlFile(projectConfigFile, migrationResult)
      this.interactor.displayText(`Project configuration migrated to version ${migrationResult.version}`)
    }

    const projectConfig = migrationResult

    const projectPath: string | undefined = projectConfig?.path
    if (!projectPath) {
      this.interactor.error('Invalid selection, project path needed 😢.')
      return
    }

    const selectedProject: SelectedProject = {
      name,
      config: projectConfig!,
      configPath: projectConfigPath,
    }
    this.updateSelectedProject(selectedProject)
    this.interactor.displayText(`Project local configuration used: ${projectConfigFile}`)
    this.interactor.sendEvent(new ProjectSelectedEvent({ projectName: name }))
  }

  private updateSelectedProject(selectedProject: SelectedProject): void {
    this.selectedProjectBehaviorSubject.next(selectedProject)
  }

  get selectedProject(): SelectedProject {
    return this.selectedProjectBehaviorSubject.value
  }

  resetProjectSelection(): void {
    this.updateSelectedProject(null)
  }

  save(update: Partial<ProjectLocalConfig>): void {
    const current = this.selectedProjectBehaviorSubject.value
    if (!current) {
      this.interactor.error(`No current project selected, save not possible`)
      return
    }
    const updated: ProjectLocalConfig = { ...current.config, ...update }
    writeYamlFile(path.join(current.configPath, PROJECT_FILENAME), updated)
    current.config = updated
    this.updateSelectedProject(current)
  }
}
