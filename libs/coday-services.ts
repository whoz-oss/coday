import { UserService } from './service/user.service'
import { ProjectService } from './service/project.service'
import { IntegrationService } from './service/integration.service'
import { IntegrationConfigService } from './service/integration-config.service'
import { MemoryService } from './service/memory.service'
import { AgentService } from './agent/agent.service'
import { McpConfigService } from './service/mcp-config.service'
import { AiConfigService } from './service/ai-config.service'
import { WebhookService } from './service/webhook.service'
import { CodayLogger } from './service/coday-logger'
import { FeedbackService } from './service/feedback.service'

export type CodayServices = {
  user: UserService
  project: ProjectService
  integration: IntegrationService
  integrationConfig: IntegrationConfigService
  memory: MemoryService
  mcp: McpConfigService
  logger: CodayLogger
  agent?: AgentService
  aiConfig?: AiConfigService
  webhook?: WebhookService
  feedback?: FeedbackService
}
