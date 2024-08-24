import {IntegrationName} from "./integration-name"
import {IntegrationConfig} from "./integration-config"

export interface ThreadInfo {
  name: string;
  // Add other relevant data if needed
}

export type SavedThreads = {
  [threadId: string]: ThreadInfo;
} | undefined

export type IntegrationLocalConfig = {
  [key in IntegrationName]?: IntegrationConfig
}

export type ProjectLocalConfig = {
  path: string
  integration: IntegrationLocalConfig,
  savedThreads: SavedThreads
}
