import {configService, ConfigService} from "./config.service"
import {ConcreteIntegrations, IntegrationConfig, IntegrationLocalConfig, Integrations} from "../model"

const API_KEY_SUFFIX = "_API_KEY"

export class IntegrationService {
  integrations: IntegrationLocalConfig | undefined
  
  constructor(private configService: ConfigService) {
    configService.selectedProject$.subscribe(selectedProject => this.integrations = selectedProject?.config?.integration)
  }
  
  /**
   * Checks whether the current project has an integration that covers the given need
   * @param name
   */
  hasIntegration(name: string): boolean {
    const implementations = Integrations[name]
    if (!implementations || !this.integrations) {
      return false // should not happen but defaulting to not integrated is ok
    }
    if (implementations.length) {
      return implementations.some(i => this.hasIntegration(i))
    }
    return Object.keys(this.integrations!).includes(name)
  }
  
  getIntegration(integrationName: string): IntegrationConfig | undefined {
    return this.integrations![integrationName]
  }
  
  getApiKey(integrationName: string): string | undefined {
    if (!(ConcreteIntegrations.includes(integrationName)) || !this.integrations) {
      return undefined
    }
    
    // shortcut if an env var is set for this typedKey
    const envApiKey: string | undefined = process.env[`${integrationName}${API_KEY_SUFFIX}`]
    if (envApiKey) {
      return envApiKey
    }
    return this.integrations[integrationName]?.apiKey
  }
  
  setIntegration(integrationName: string, integration: IntegrationConfig) {
    if (!this.integrations) {
      return
    }
    this.integrations[integrationName] = integration
    this.configService.saveProjectConfig({integration: this.integrations})
  }
  
  getApiUrl(integrationName: string): string | undefined {
    if (!this.integrations) {
      return undefined
    }
    return this.integrations[integrationName]?.apiUrl
  }
  
  getUsername(integrationName: string): string | undefined {
    if (!this.integrations) {
      return undefined
    }
    return this.integrations[integrationName]?.username
  }
  
  hasAiIntegration(aiProviderName: string) {
    return false
  }
}

export const integrationService = new IntegrationService(configService)