import { IntegrationConfig } from './integration-config'
import { AgentDefinition } from './agent-definition'

export interface ThreadInfo {
  name: string
  // Add other relevant data if needed
}

export type IntegrationLocalConfig = {
  [key: string]: IntegrationConfig
}

export type ProjectLocalConfig = {
  path: string
  integration: IntegrationLocalConfig
  storage?: StorageConfig
  agents?: AgentDefinition | AgentDefinition[]
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
