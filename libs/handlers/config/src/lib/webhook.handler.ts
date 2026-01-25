import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { WebhookAddHandler, WebhookDeleteHandler, WebhookEditHandler, WebhookListHandler } from '@coday/handlers/config'

/**
 * Root handler for all webhook config commands: list, add, edit, delete.
 */
export class WebhookHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'webhook',
        description: 'Manage webhook configurations for external integrations',
      },
      interactor
    )

    // Create edit handler first for passing to add handler (delegation pattern)
    const webhookEditHandler = new WebhookEditHandler(interactor, services)

    this.handlers = [
      new WebhookListHandler(interactor, services),
      new WebhookAddHandler(interactor, services, webhookEditHandler),
      webhookEditHandler,
      new WebhookDeleteHandler(interactor, services),
    ]
  }
}
