import * as path from 'node:path'
import * as os from 'node:os'
import { mkdirSync } from 'fs'
import { BehaviorSubject, Observable } from 'rxjs'
import { Interactor, ProjectLocalConfig, SelectedProject } from '@coday/model'
import { writeYamlFile } from './write-yaml-file'
import { ConfigMaskingService } from './config-masking.service'

const PROJECTS = 'projects'
const PROJECT_FILENAME = 'project.yaml'

export class ProjectStateService {
  private readonly projectsConfigPath: string
  private readonly maskingService = new ConfigMaskingService()

  /**
   * List of project names, as taken from the folder existing in the config directory
   * Serves as a marker of initialized if not null
   * @public
   */
  projects: string[]

  private readonly selectedProjectBehaviorSubject = new BehaviorSubject<SelectedProject>(null)
  selectedProject$: Observable<SelectedProject> = this.selectedProjectBehaviorSubject.asObservable()

  constructor(
    private readonly interactor: Interactor,
    private readonly projectService: ProjectService,
    codayConfigPath: string | undefined
  ) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.projectsConfigPath = path.join(codayConfigPath ?? defaultConfigPath, PROJECTS)
    // Ensure the user's directory exists
    mkdirSync(this.projectsConfigPath, { recursive: true })
    this.projects = this.projectService.listProjects().map((v) => v.name)
  }

  selectProject(name: string): void {
    const projectConfigPath = path.join(this.projectsConfigPath, name)
    const projectConfigFile = path.join(projectConfigPath, PROJECT_FILENAME)

    // const projectConfig = migrationResult
    const projectConfig = this.projectService.getProject(name)?.config

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
  }

  private updateSelectedProject(selectedProject: SelectedProject): void {
    this.selectedProjectBehaviorSubject.next(selectedProject)
  }

  get selectedProject(): SelectedProject {
    return this.selectedProjectBehaviorSubject.value
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

  /**
   * Get configuration with sensitive values masked for client display
   */
  getConfigForClient(): ProjectLocalConfig | null {
    const current = this.selectedProjectBehaviorSubject.value
    if (!current) {
      return null
    }
    return this.maskingService.maskConfig(current.config)
  }

  /**
   * Update configuration from client, unmasking to preserve original sensitive values
   */
  updateConfigFromClient(incomingConfig: ProjectLocalConfig): void {
    const current = this.selectedProjectBehaviorSubject.value
    if (!current) {
      this.interactor.error(`No current project selected, update not possible`)
      return
    }
    const unmaskedConfig = this.maskingService.unmaskConfig(incomingConfig, current.config)
    this.save(unmaskedConfig)
  }
}
