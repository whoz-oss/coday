import { Interactor, NestedHandler } from '@coday/model'
import { CodayServices } from '../../coday-services'
import { IntegrationListHandler } from './integration-list.handler'
import { IntegrationAddHandler } from './integration-add.handler'
import { IntegrationEditHandler } from './integration-edit.handler'
import { IntegrationDeleteHandler } from './integration-delete.handler'

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
