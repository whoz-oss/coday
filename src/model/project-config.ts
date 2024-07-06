import {IntegrationName} from "./integration-name";
import {IntegrationConfig} from "./integration-config";

export type ProjectConfig = {
  path: string
  integration: {
    [key in IntegrationName]?: IntegrationConfig
  }
}