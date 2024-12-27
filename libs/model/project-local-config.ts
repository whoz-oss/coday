import { IntegrationConfig } from './integration-config'
import { AgentDefinition } from './agent-definition'
import { AiProviderLocalConfig } from './ai-providers'

export type IntegrationLocalConfig = {
  [key: string]: IntegrationConfig
}

export type ProjectLocalConfig = {
  path: string
  aiProviders: AiProviderLocalConfig
  integration: IntegrationLocalConfig
  storage?: StorageConfig
  agents?: AgentDefinition[]
}

export type StorageConfig =
  | {
      type: 'file'
    }
  | {
      type: 'mongo'
      uri: string
      database: string
    }
  | {
      type: 'postgres'
      host: string
      port: number
      database: string
      user: string
      password: string
    }

export type SelectedProject = {
  name: string
  configPath: string
  config: ProjectLocalConfig
} | null
