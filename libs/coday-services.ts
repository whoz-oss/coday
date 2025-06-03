import { UserService } from './service/user.service'
import { ProjectService } from './service/project.service'
import { IntegrationService } from './service/integration.service'
import { MemoryService } from './service/memory.service'
import { AgentService } from './agent/agent.service'
import { McpConfigService } from './service/mcp-config.service'
import { UsageLogger } from './service/usage-logger'

export type CodayServices = {
  user: UserService
  project: ProjectService
  integration: IntegrationService
  memory: MemoryService
  mcp: McpConfigService
  usageLogger: UsageLogger
  agent?: AgentService
}
