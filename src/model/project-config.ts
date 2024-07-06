import {IntegrationName} from "./integration-name"
import {IntegrationConfig} from "./integration-config"

export interface ThreadInfo {
  name: string;
  // Add other relevant data if needed
}

export type ProjectConfig = {
  path: string
  integration: {
    [key in IntegrationName]?: IntegrationConfig
  },
  savedThreads?: {
    [threadId: string]: ThreadInfo;
  }
}
