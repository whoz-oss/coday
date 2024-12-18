import { Interactor, NestedHandler } from '../../model'
import { CodayServices } from '../../coday-services'
import { UserAiConfigHandler } from './user-ai-config.handler'
import { ProjectAiConfigHandler } from './project-ai-config.handler'

export class AiConfigHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    services: CodayServices
  ) {
    super(
      {
        commandWord: 'ai',
        description: 'Manage AI provider configurations (user or project scope)',
      },
      interactor
    )

    this.handlers = [
      new UserAiConfigHandler(interactor, services),
      new ProjectAiConfigHandler(interactor, services)
    ]
  }
}