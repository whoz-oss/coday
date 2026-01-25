import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { AiModelAddHandler, AiModelDeleteHandler, AiModelEditHandler, AiModelListHandler } from '@coday/handlers/config'

/**
 * Nested handler for managing AI models within provider configs.
 */
export class AiModelHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'model',
        description: 'Manage AI models for provider configurations',
      },
      interactor
    )
    this.handlers = [
      new AiModelListHandler(interactor, services),
      new AiModelAddHandler(interactor, services, new AiModelEditHandler(interactor, services)),
      new AiModelEditHandler(interactor, services),
      new AiModelDeleteHandler(interactor, services),
    ]
  }
}
