import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

/**
 * Handler for deleting a model from a provider config.
 */
export class AiModelDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete a model from an AI provider configuration.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Prompt for provider/model, confirm, then delete
    this.interactor.displayText('[TODO] Delete model from AI config')
    return context
  }
}
