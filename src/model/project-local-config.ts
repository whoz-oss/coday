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

export type AiProviderConfig = {
  apiKey: string
}

export type AiConfig = {
  anthropic?: AiProviderConfig
  openai?: AiProviderConfig
  gemini?: AiProviderConfig
}

export type ProjectLocalConfig = {
  path: string
  integration: IntegrationLocalConfig,
  savedThreads: SavedThreads,
  ai: AiConfig
}