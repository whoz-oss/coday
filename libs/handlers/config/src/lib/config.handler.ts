import { NestedHandler } from '@coday/handler'
import { AiConfigHandler } from './ai-config.handler'
import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { McpConfigHandler } from './mcp-config/mcp-config.handler'
import { SelectProjectHandler } from './select-project.handler'
import { IntegrationHandler } from './integration.handler'
import { DefaultAgentHandler } from './default-agent.handler'
import { UserBioHandler } from './user-bio.handler'
import { CostLimitHandler } from './cost-limit.handler'

export class ConfigHandler extends NestedHandler {
  selectProjectHandler: SelectProjectHandler

  constructor(
    interactor: Interactor,
    private services: CodayServices
  ) {
    super(
      {
        commandWord: 'config',
        description: 'handles config related commands',
      },
      interactor
    )

    this.selectProjectHandler = new SelectProjectHandler(this.interactor, this.services)
    this.handlers = [
      new IntegrationHandler(this.interactor, this.services),
      new AiConfigHandler(this.interactor, this.services),
      new DefaultAgentHandler(this.interactor, this.services),
      new McpConfigHandler(this.interactor, this.services),
      new UserBioHandler(this.interactor, this.services),
      new CostLimitHandler(this.interactor),
    ]
  }
}
