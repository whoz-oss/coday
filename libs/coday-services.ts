import { UserService } from './service/user.service'
import { ProjectService } from './service/project.service'
import { IntegrationService } from './service/integration.service'
import { MemoryService } from './service/memory.service'

export type CodayServices = {
  user: UserService
  project: ProjectService
  integration: IntegrationService
  memory: MemoryService
}
