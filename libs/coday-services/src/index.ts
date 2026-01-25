import { UserService } from './service/user.service'
import { ProjectStateService } from '@coday/service/project-state.service'
import { IntegrationService } from './service/integration.service'
import { IntegrationConfigService } from './service/integration-config.service'
import { MemoryService } from './service/memory.service'
import { AgentService } from './agent'
import { McpConfigService } from './service/mcp-config.service'
import { AiConfigService } from './service/ai-config.service'
import { WebhookService } from './service/webhook.service'
import { CodayLogger } from './service/coday-logger'
import { McpInstancePool } from './integration/mcp/mcp-instance-pool'
import { ThreadService } from '../apps/server/src/services/thread.service'
import { CodayOptions } from './options'

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
