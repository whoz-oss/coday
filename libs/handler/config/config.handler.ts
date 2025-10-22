import { SelectProjectHandler } from './select-project.handler'
import { IntegrationHandler } from './integration.handler'
import { AiConfigHandler } from './ai-config.handler'
import { DefaultAgentHandler } from './default-agent.handler'
import { McpConfigHandler } from './mcp-config/mcp-config.handler'
import { WebhookHandler } from './webhook.handler'
import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
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
      new WebhookHandler(this.interactor, this.services),
      new UserBioHandler(this.interactor, this.services),
      new CostLimitHandler(this.interactor),
    ]
  }
}
