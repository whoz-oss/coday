import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

/**
 * Handler for editing a single model of a provider config at a precise level (default user).
 * Interactive, property-by-property editing.
 */
export class AiModelEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit a model of an AI provider configuration (default user level).',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Prompt for provider/model, then field-by-field edit
    this.interactor.displayText('[TODO] Edit model (property-by-property)')
    return context
  }
}
