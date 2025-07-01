import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
import { StatsAgentsHandler } from './stats-agents.handler'
import { StatsWebhooksHandler } from './stats-webhooks.handler'

export class StatsHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    private services: CodayServices
  ) {
    super(
      {
        commandWord: 'stats',
        description: 'Show usage statistics and analytics',
        isInternal: true,
      },
      interactor
    )

    this.handlers = [
      new StatsAgentsHandler(this.interactor, this.services),
      new StatsWebhooksHandler(this.interactor, this.services)
    ]
  }
}
