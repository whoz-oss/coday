import { NestedHandler } from '@coday/handler'
import {
  AiConfigHandler,
  CostLimitHandler,
  DefaultAgentHandler,
  IntegrationHandler,
  SelectProjectHandler,
  UserBioHandler,
  WebhookHandler,
} from '@coday/handlers/config'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { McpConfigHandler } from './mcp-config/mcp-config.handler'

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
