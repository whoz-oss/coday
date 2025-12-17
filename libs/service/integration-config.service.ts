import { Interactor } from '@coday/model/interactor'
import { UserService } from './user.service'
import { ProjectStateService } from './project-state.service'
import { IntegrationConfig, IntegrationLocalConfig } from '@coday/model'
import { ConfigLevel, ConfigLevelValidator } from '@coday/model/config-level'

const MASKED_VALUE = '********'

/**
 * Service for managing integration configurations at USER and PROJECT levels.
 * Handles CONFLUENCE, GIT, GITLAB, JIRA integrations with API key masking.
 */
export class IntegrationConfigService {
  constructor(
    private readonly userService: UserService,
    private readonly projectService: ProjectStateService,
    private readonly interactor: Interactor
  ) {}

  /**
   * Get integrations at a specific level with API key masking
   */
  getIntegrations(level: ConfigLevel): IntegrationLocalConfig {
    ConfigLevelValidator.validate(level)

    if (level === ConfigLevel.CODAY) {
      throw new Error('CODAY level not supported for integrations')
    }

    let integrations: IntegrationLocalConfig = {}

    if (level === ConfigLevel.PROJECT) {
      const project = this.projectService.selectedProject
      if (!project) {
        return {}
      }
      integrations = project.config.integration || {}
    } else if (level === ConfigLevel.USER) {
      const project = this.projectService.selectedProject
      if (!project) {
        return {}
      }
      const userProjects = this.userService.config.projects || {}
      integrations = userProjects[project.name]?.integration || {}
    }

    // Mask API keys for security
    return this.maskApiKeys(integrations)
  }

  /**
   * Save/update an integration configuration at the specified level
   */
  async saveIntegration(name: string, config: IntegrationConfig, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)

    if (level === ConfigLevel.CODAY) {
      throw new Error('CODAY level not supported for integrations')
    }

    if (level === ConfigLevel.PROJECT) {
      const project = this.projectService.selectedProject
      if (!project) {
        throw new Error('No project selected')
      }

      // Get current project integrations
      const currentIntegrations = project.config.integration || {}

      // Update the specific integration
      const updatedIntegrations = {
        ...currentIntegrations,
        [name]: config,
      }

      // Save project configuration
      this.projectService.save({
        integration: updatedIntegrations,
      })
    } else if (level === ConfigLevel.USER) {
      const project = this.projectService.selectedProject
      if (!project) {
        throw new Error('No project selected')
      }

      // Use existing UserService method for user-level integration
      this.userService.setProjectIntegration(project.name, { [name]: config })
    }

    this.interactor.debug(`Integration '${name}' saved at ${level.toLowerCase()} level`)
  }

  /**
   * Delete an integration configuration at the specified level
   */
  async deleteIntegration(name: string, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)

    if (level === ConfigLevel.CODAY) {
      throw new Error('CODAY level not supported for integrations')
    }

    if (level === ConfigLevel.PROJECT) {
      const project = this.projectService.selectedProject
      if (!project) {
        throw new Error('No project selected')
      }

      // Get current project integrations
      const currentIntegrations = project.config.integration || {}

      // Check if integration exists
      if (!currentIntegrations[name]) {
        throw new Error(`Integration '${name}' not found at project level`)
      }

      // Remove the integration
      const updatedIntegrations = { ...currentIntegrations }
      delete updatedIntegrations[name]

      // Save updated project configuration
      this.projectService.save({
        integration: updatedIntegrations,
      })
    } else if (level === ConfigLevel.USER) {
      const project = this.projectService.selectedProject
      if (!project) {
        throw new Error('No project selected')
      }

      // Get current user integrations for this project
      const userProjects = this.userService.config.projects || {}
      const currentUserIntegrations = userProjects[project.name]?.integration || {}

      // Check if integration exists
      if (!currentUserIntegrations[name]) {
        throw new Error(`Integration '${name}' not found at user level`)
      }

      // Remove the integration by setting it to undefined and filtering
      const updatedIntegrations = { ...currentUserIntegrations }
      delete updatedIntegrations[name]

      // Ensure projects structure exists
      if (!this.userService.config.projects) {
        this.userService.config.projects = {}
      }
      if (!this.userService.config.projects[project.name]) {
        this.userService.config.projects[project.name] = { integration: {} }
      }

      // Update user configuration
      this.userService.config.projects[project.name]!.integration = updatedIntegrations
      this.userService.save()
    }

    this.interactor.debug(`Integration '${name}' deleted from ${level.toLowerCase()} level`)
  }

  /**
   * Get merged integrations (user overrides project) with API key masking
   */
  getMergedIntegrations(): IntegrationLocalConfig {
    const project = this.projectService.selectedProject
    if (!project) {
      return {}
    }

    // Get project-level integrations
    const projectIntegrations = project.config.integration || {}

    // Get user-level integrations
    const userProjects = this.userService.config.projects || {}
    const userIntegrations = userProjects[project.name]?.integration || {}

    // Merge: start with project, then override with user
    const merged: IntegrationLocalConfig = { ...projectIntegrations }

    // User integrations override project integrations
    Object.keys(userIntegrations).forEach((key) => {
      const userIntegration = userIntegrations[key]!
      const currentProjectIntegration = merged[key]

      if (!currentProjectIntegration) {
        // No project integration, use user integration as-is
        merged[key] = userIntegration
      } else {
        // Merge user over project integration
        merged[key] = { ...currentProjectIntegration, ...userIntegration }
      }
    })

    // Mask API keys for security
    return this.maskApiKeys(merged)
  }

  /**
   * Mask API keys in integration configurations for security
   * @private
   */
  private maskApiKeys(integrations: IntegrationLocalConfig): IntegrationLocalConfig {
    const masked: IntegrationLocalConfig = {}

    Object.keys(integrations).forEach((key) => {
      const integration = integrations[key]!
      masked[key] = {
        ...integration,
        // Mask API key if it exists
        ...(integration.apiKey && { apiKey: MASKED_VALUE }),
      }
    })

    return masked
  }

  /**
   * Check if an API key is currently masked (used by handlers to detect existing keys)
   */
  isApiKeyMasked(value: string | undefined): boolean {
    return value === MASKED_VALUE
  }

  /**
   * Get the actual (unmasked) integration configuration for internal use
   * This method should only be used when the actual API key is needed (e.g., for API calls)
   */
  getUnmaskedIntegration(name: string, level: ConfigLevel): IntegrationConfig | undefined {
    ConfigLevelValidator.validate(level)

    if (level === ConfigLevel.CODAY) {
      throw new Error('CODAY level not supported for integrations')
    }

    if (level === ConfigLevel.PROJECT) {
      const project = this.projectService.selectedProject
      if (!project) {
        return undefined
      }
      const integrations = project.config.integration || {}
      return integrations[name]
    } else if (level === ConfigLevel.USER) {
      const project = this.projectService.selectedProject
      if (!project) {
        return undefined
      }
      const userProjects = this.userService.config.projects || {}
      const integrations = userProjects[project.name]?.integration || {}
      return integrations[name]
    }

    return undefined
  }
}
