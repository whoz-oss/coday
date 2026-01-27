import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { IntegrationAddHandler } from './integration-add.handler'
import { IntegrationDeleteHandler } from './integration-delete.handler'
import { IntegrationEditHandler } from './integration-edit.handler'
import { IntegrationListHandler } from './integration-list.handler'

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
