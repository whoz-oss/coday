import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
import { StatsAgentsHandler } from './stats-agents.handler'

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

    this.handlers = [new StatsAgentsHandler(this.interactor, this.services)]
  }
}
