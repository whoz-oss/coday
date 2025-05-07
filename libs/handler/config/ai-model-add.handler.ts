import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { AiModelEditHandler } from './ai-model-edit.handler'

/**
 * Handler for adding a new model to an AI provider config.
 * Prompts for model name, creates default, then redirects to edit handler.
 * Needs a reference to edit handler to avoid code duplication.
 */
export class AiModelAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices,
    private editHandler: AiModelEditHandler,
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new model to an AI provider configuration and edit it.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Prompt for provider/model name, create, call editHandler
    this.interactor.displayText('[TODO] Add new model, then edit it')
    return context
  }
}
