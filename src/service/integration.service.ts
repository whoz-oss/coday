import {configService, ConfigService} from "./config.service"
import {IntegrationConfig, IntegrationLocalConfig, IntegrationName} from "../model"

const API_KEY_SUFFIX = "_API_KEY"

export class IntegrationService {
  integrations: IntegrationLocalConfig | undefined
  
  constructor(private configService: ConfigService) {
    configService.selectedProject$.subscribe(selectedProject => this.integrations = selectedProject?.config?.integration)
  }
  
  hasIntegration(name: IntegrationName): boolean {
    return Object.keys(this.integrations!).includes(name)
  }
  
  getIntegration(name: IntegrationName): IntegrationConfig | undefined {
    return this.integrations![name]
  }
  
  getApiKey(keyName: string): string | undefined {
    if (!(keyName in IntegrationName)) {
      return undefined
    }
    const apiName: IntegrationName = keyName as unknown as IntegrationName
    if (!this.integrations) {
      return
    }
    // shortcut if an env var is set for this typedKey
    const envApiKey: string | undefined = process.env[`${apiName}${API_KEY_SUFFIX}`]
    if (envApiKey) {
      return envApiKey
    }
    return this.integrations[apiName]?.apiKey
  }
  
  setIntegration(selectedName: IntegrationName, integration: IntegrationConfig) {
    if (!this.integrations) {
      return
    }
    this.integrations[selectedName] = integration
    this.configService.saveProjectConfig({integration: this.integrations})
  }
  
  getApiUrl(apiName: IntegrationName): string | undefined {
    if (!this.integrations) {
      return undefined
    }
    return this.integrations[apiName]?.apiUrl
  }
  
  getUsername(apiName: IntegrationName): string | undefined {
    if (!this.integrations) {
      return undefined
    }
    return this.integrations[apiName]?.username
  }
}

export const integrationService = new IntegrationService(configService)