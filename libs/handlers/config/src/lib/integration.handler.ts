import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import {
  IntegrationAddHandler,
  IntegrationDeleteHandler,
  IntegrationEditHandler,
  IntegrationListHandler,
} from '@coday/handlers/config'

export class IntegrationHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'integration',
        description: 'Manage integration configurations (user or project scope)',
      },
      interactor
    )

    // Create edit handler first so it can be referenced by add handler
    const editHandler = new IntegrationEditHandler(interactor, services.integrationConfig)

    this.handlers = [
      new IntegrationListHandler(interactor, services.integrationConfig),
      editHandler,
      new IntegrationAddHandler(interactor, services.integrationConfig, editHandler),
      new IntegrationDeleteHandler(interactor, services.integrationConfig),
    ]
  }
}
