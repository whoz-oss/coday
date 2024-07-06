import {IntegrationName} from "../model/integration-name"
import {ProjectConfig} from "../model/project-config"
import {IntegrationConfig} from "../model/integration-config"
import {configService, ConfigService} from "./config.service"

const API_KEY_SUFFIX = "_API_KEY"

export class IntegrationService {
  
  constructor(private service: ConfigService) {
  }
  
  hasIntegration(name: IntegrationName): boolean {
    this.service.initConfig()
    return Object.keys(this.service.getProject()!.integration).includes(name)
  }
  
  get integrations() {
    const project = this.service.getProject()
    return project!.integration!
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
    
    const project: ProjectConfig | undefined = this.service.getProject()
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
    const project = this.service.getProject()
    if (!project) {
      return
    }
    project.integration[selectedName] = integration
    this.service.saveConfigFile()
  }
  
  getApiUrl(apiName: IntegrationName): string | undefined {
    const project: ProjectConfig | undefined = this.service.getProject()
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
    const project: ProjectConfig | undefined = this.service.getProject()
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