import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
import { AiConfigListHandler } from './ai-config-list.handler'
import { AiConfigAddHandler } from './ai-config-add.handler'
import { AiConfigEditHandler } from './ai-config-edit.handler'
import { AiConfigDeleteHandler } from './ai-config-delete.handler'
import { AiModelHandler } from './ai-model.handler'

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
      new AiConfigDeleteHandler(interactor, services),
      new AiModelHandler(interactor, services),
    ]
  }
}
