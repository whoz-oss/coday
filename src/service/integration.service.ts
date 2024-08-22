import {configService, ConfigService} from "./config.service"
import {IntegrationConfig, IntegrationName, ProjectConfig} from "../model"

const API_KEY_SUFFIX = "_API_KEY"

export class IntegrationService {
  
  constructor(private configService: ConfigService) {
  }
  
  hasIntegration(name: IntegrationName): boolean {
    return Object.keys(this.configService.project!.integration).includes(name)
  }
  
  get integrations() {
    const project = this.configService.project
    return project!.integration!
  }
  
  getIntegration(name: IntegrationName): IntegrationConfig | undefined {
    return this.configService.project!.integration[name]
  }
  
  getApiKey(keyName: string): string | undefined {
    if (!(keyName in IntegrationName)) {
      return undefined
    }
    const apiName: IntegrationName = keyName as unknown as IntegrationName
    const envApiKey: string | undefined = process.env[`${apiName}${API_KEY_SUFFIX}`]
    // shortcut if an env var is set for this typedKey
    if (envApiKey) {
      return envApiKey
    }
    
    const project: ProjectConfig | undefined = this.configService.project
    if (!project) {
      return undefined
    }
    let integration: IntegrationConfig | undefined = project.integration[apiName]
    if (!integration) {
      return undefined
    }
    return integration.apiKey
  }
  
  setIntegration(selectedName: IntegrationName, integration: IntegrationConfig) {
    const project = this.configService.project
    if (!project) {
      return
    }
    project.integration[selectedName] = integration
    this.configService.saveProjectConfig()
  }
  
  getApiUrl(apiName: IntegrationName): string | undefined {
    const project: ProjectConfig | undefined = this.configService.project
    if (!project) {
      return undefined
    }
    let integration: IntegrationConfig | undefined = project.integration[apiName]
    if (!integration) {
      return undefined
    }
    return integration.apiUrl
  }
  
  getUsername(apiName: IntegrationName): string | undefined {
    const project: ProjectConfig | undefined = this.configService.project
    if (!project) {
      return undefined
    }
    let integration: IntegrationConfig | undefined = project.integration[apiName]
    if (!integration) {
      return undefined
    }
    return integration.username
  }
}

export const integrationService = new IntegrationService(configService)