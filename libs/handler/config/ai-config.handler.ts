import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
import { ProjectAiConfigHandler } from './project-ai-config.handler'

export class AiConfigHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'ai',
        description: 'Manage AI provider configurations (user or project scope)',
      },
      interactor
    )

    this.handlers = [new ProjectAiConfigHandler(interactor, services)]
  }
}
