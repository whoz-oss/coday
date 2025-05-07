import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

/**
 * Handler for listing all AI provider configurations.
 * Lists all providers/models from merged configs for full client view.
 */
export class AiConfigListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'list',
      description: 'List all AI provider configurations (merged user/project/global view).',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Implement merged config listing using AiConfigService
    this.interactor.displayText('[TODO] List all AI configs (merged/global/project/user)')
    return context
  }
}
