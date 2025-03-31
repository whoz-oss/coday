import { SelectProjectHandler } from './select-project.handler'
import { IntegrationHandler } from './integration.handler'
import { AiConfigHandler } from './ai-config.handler'
import { DefaultAgentHandler } from './default-agent.handler'
import { McpConfigHandler } from './mcp-config.handler'
import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'

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
      this.selectProjectHandler,
      new IntegrationHandler(this.interactor, this.services),
      new AiConfigHandler(this.interactor, this.services),
      new DefaultAgentHandler(this.interactor, this.services),
      new McpConfigHandler(this.interactor, this.services),
    ]
  }
}
