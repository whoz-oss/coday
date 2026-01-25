import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import {
  AiConfigAddHandler,
  AiConfigApikeyHandler,
  AiConfigDeleteHandler,
  AiConfigEditHandler,
  AiConfigListHandler,
  AiModelHandler,
} from '@coday/handlers/config'

/**
 * Root handler for all AI config commands: list, add, edit, delete, and nested model commands.
 */
export class AiConfigHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'ai',
        description: 'Manage AI provider configurations (user or project scope)',
      },
      interactor
    )

    // Create edit handlers for passing to add handlers (to avoid duplication)
    const aiConfigEditHandler = new AiConfigEditHandler(interactor, services)

    this.handlers = [
      new AiConfigListHandler(interactor, services),
      new AiConfigAddHandler(interactor, services, aiConfigEditHandler),
      aiConfigEditHandler,
      new AiConfigApikeyHandler(interactor, services),
      new AiConfigDeleteHandler(interactor, services),
      new AiModelHandler(interactor, services),
    ]
  }
}
