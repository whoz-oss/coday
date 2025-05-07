import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { AiConfigEditHandler } from './ai-config-edit.handler'

/**
 * Handler for adding a new AI provider configuration.
 * Prompts for provider name, creates default config, then redirects to edit handler.
 * Needs a reference to the edit handler to avoid code duplication.
 */
export class AiConfigAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices,
    private editHandler: AiConfigEditHandler,
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new AI provider configuration and edit it.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Prompt for provider name, create default, call editHandler
    this.interactor.displayText('[TODO] Add new AI config, then edit it')
    return context
  }
}
