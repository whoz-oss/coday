import { CommandHandler, CommandContext } from '../../model'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

/**
 * Handler for listing all models (merged) and those defined at each config level.
 */
export class AiModelListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'list',
      description: 'List all models (merged/project/user/global, no --project flag)',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // TODO: Implement model listing for merged and per-level configs
    this.interactor.displayText('[TODO] List all models (merged/user/project/global)')
    return context
  }
}
