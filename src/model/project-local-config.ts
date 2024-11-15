import {IntegrationConfig} from "./integration-config"

export interface ThreadInfo {
  name: string;
  // Add other relevant data if needed
}

export type SavedThreads = {
  [threadId: string]: ThreadInfo;
} | undefined

export type IntegrationLocalConfig = {
  [key: string]: IntegrationConfig
}

export type ProjectLocalConfig = {
  path: string
  integration: IntegrationLocalConfig,
  savedThreads: SavedThreads
  storage?: StorageConfig
}

export type StorageConfig = {
  type: "file"
} | {
  type: "mongo"
  uri: string
  database: string
} | {
  type: "postgres"
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