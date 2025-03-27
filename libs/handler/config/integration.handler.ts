import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
import { UserIntegrationHandler } from './user-integration.handler'
import { ProjectIntegrationHandler } from './project-integration.handler'

export class IntegrationHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'integration',
        description: 'Manage integration configurations (user or project scope)',
      },
      interactor
    )

    this.handlers = [
      new UserIntegrationHandler(interactor, services),
      new ProjectIntegrationHandler(interactor, services),
    ]
  }
}
