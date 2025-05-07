import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

/**
 * Handler for deleting an AI provider configuration and all its models.
 */
export class AiConfigDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete an AI provider configuration and all its models.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Prompt for provider to delete, confirm, then delete from config
    this.interactor.displayText('[TODO] Delete AI config (with all models)')
    return context
  }
}
