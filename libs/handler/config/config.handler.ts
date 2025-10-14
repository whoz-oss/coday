import { SelectProjectHandler } from './select-project.handler'
import { McpConfigHandler } from './mcp-config/mcp-config.handler'
import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
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
      this.selectProjectHandler,
      new McpConfigHandler(this.interactor, this.services),
      new CostLimitHandler(this.interactor),
    ]
  }
}
