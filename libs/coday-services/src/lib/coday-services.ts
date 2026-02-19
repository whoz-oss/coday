import type {
  AiConfigService,
  IntegrationConfigService,
  IntegrationService,
  McpConfigService,
  MemoryService,
  ProjectStateService,
  PromptService,
  UserService,
} from '@coday/service'
import { ThreadService } from '@coday/service'
import { McpInstancePool } from '@coday/mcp'
import { AgentServiceModel, CodayLogger, CodayOptions } from '@coday/model'

export type CodayServices = {
  user: UserService
  project: ProjectStateService
  integration: IntegrationService
  thread: ThreadService
  integrationConfig: IntegrationConfigService
  memory: MemoryService
  mcp: McpConfigService
  mcpPool: McpInstancePool
  logger: CodayLogger
  prompt: PromptService
  options?: CodayOptions
  agent?: AgentServiceModel
  aiConfig?: AiConfigService
}
