import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

/**
 * Handler for editing an existing AI provider configuration.
 * Interactive, property-by-property editing (excluding models).
 */
export class AiConfigEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit an existing AI provider configuration (excluding models).',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Prompt for provider to edit, then field-by-field edit excluding models
    this.interactor.displayText('[TODO] Edit AI config (no models)')
    return context
  }
}
