import { ProjectStateService } from './project-state.service'
import { UserService } from './user.service'
import { IntegrationLocalConfig } from '@coday/model/project-local-config'
import { Interactor } from '@coday/model/interactor'
import { ConcreteIntegrations, Integrations } from '@coday/model/integrations'
import { IntegrationConfig } from '@coday/model/integration-config'

const API_KEY_SUFFIX = '_API_KEY'

export class IntegrationService {
  integrations: IntegrationLocalConfig = {}
  projectIntegrations: IntegrationLocalConfig = {}
  userIntegrations: IntegrationLocalConfig = {}

  constructor(
    private readonly projectService: ProjectStateService,
    private readonly userService: UserService,
    private readonly interactor?: Interactor
  ) {
    projectService.selectedProject$.subscribe((selectedProject) => {
      // Ensure we only proceed if a project is selected
      if (!selectedProject) {
        this.projectIntegrations = {}
        this.userIntegrations = {}
        this.integrations = {}
        return
      }

      // Project Integrations
      this.projectIntegrations = selectedProject.config.integration || {}

      // User Integrations
      this.userIntegrations =
        selectedProject.name && this.userService.config.projects
          ? this.userService.config.projects[selectedProject.name]?.integration || {}
          : {}

      // Merge the integrations
      this.integrations = { ...this.projectIntegrations }

      // Safely merge user integrations with deep merge for oauth2
      Object.keys(this.userIntegrations).forEach((key) => {
        const userIntegration = this.userIntegrations[key]!
        const currentProjectIntegration = this.integrations[key]

        if (!currentProjectIntegration) {
          this.integrations[key] = userIntegration
        } else {
          // Deep merge: handle oauth2 object specially to preserve both PROJECT and USER properties
          const merged = { ...currentProjectIntegration, ...userIntegration }

          // If both have oauth2, merge them deeply instead of replacing
          if (currentProjectIntegration.oauth2 && userIntegration.oauth2) {
            merged.oauth2 = { ...currentProjectIntegration.oauth2, ...userIntegration.oauth2 }
          }

          this.integrations[key] = merged
        }
      })
    })
  }

  /**
   * Checks whether the current project has an integration that covers the given need
   * @param name
   */
  hasIntegration(name: string): boolean {
    const implementations = Integrations[name]
    if (!implementations) {
      return false
    }
    if (implementations.length) {
      return implementations.some((i) => this.hasIntegration(i))
    }
    return Object.keys(this.integrations).includes(name)
  }

  getIntegration(integrationName: string): IntegrationConfig | undefined {
    return this.integrations[integrationName]
  }

  getApiKey(integrationName: string): string | undefined {
    if (!ConcreteIntegrations.includes(integrationName)) {
      return undefined
    }

    // shortcut if an env var is set for this typedKey
    const envApiKey: string | undefined = process.env[`${integrationName}${API_KEY_SUFFIX}`]
    if (envApiKey) {
      // Optional: log the usage of env var through the interactor
      this.interactor?.displayText(`Using environment variable for ${integrationName} API key`)
      return envApiKey
    }

    return this.integrations[integrationName]?.apiKey
  }

  setIntegration(integrationName: string, integration: IntegrationConfig) {
    this.integrations[integrationName] = integration
    this.projectService.save({ integration: this.integrations })
  }

  getApiUrl(integrationName: string): string | undefined {
    return this.integrations[integrationName]?.apiUrl
  }

  getUsername(integrationName: string): string | undefined {
    return this.integrations[integrationName]?.username
  }
}
