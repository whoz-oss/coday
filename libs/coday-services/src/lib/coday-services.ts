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
import type { McpInstancePool } from '@coday/mcp/src'
import type { CodayOptions } from '@coday/model/options'
import type { AgentService } from '@coday/agent'
import type { ThreadService } from 'apps/server/src/services/thread.service'

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
