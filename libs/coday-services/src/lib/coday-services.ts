import type {
  AiConfigService,
  CodayLogger,
  IntegrationConfigService,
  IntegrationService,
  McpConfigService,
  MemoryService,
  ProjectStateService,
  UserService,
  WebhookService,
} from '@coday/service'
import { ThreadService } from '@coday/service'
import { McpInstancePool } from '@coday/mcp'
import { CodayOptions } from '@coday/model'
import { AgentService } from '@coday/agent'

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
  options?: CodayOptions
  agent?: AgentService
  aiConfig?: AiConfigService
  webhook?: WebhookService
}
