import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { StatsAgentsHandler, StatsWebhooksHandler } from '@coday/handlers/stats'

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
      new StatsWebhooksHandler(this.interactor, this.services),
    ]
  }
}
