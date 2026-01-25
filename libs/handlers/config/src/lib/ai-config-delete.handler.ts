import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model/config-level'

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
      description:
        'Delete an AI provider configuration and all its models. User level is default, use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.services.aiConfig) {
      this.interactor.displayText('AI config service unavailable.')
      return context
    }

    // Determine config level (user/project)
    const args = parseArgs(command, [{ key: 'project', alias: 'p' }])
    const isProject = !!args.project
    const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Get providers at this level
    const providers = this.services.aiConfig.getProviders(level)
    if (!providers.length) {
      this.interactor.displayText(`No AI providers found at ${level} level. Nothing to delete.`)
      return context
    }

    // Present provider list
    const providerNames = providers.map((p) => p.name)
    const chosenName = await this.interactor.chooseOption(
      providerNames,
      `Select AI provider to delete from ${level} config:`
    )
    if (!chosenName) return context

    // Confirm deletion (using chooseOption yes/no)
    const confirmChoice = await this.interactor.chooseOption(
      ['yes', 'no'],
      `Are you sure you want to delete provider '${chosenName}' and all its models from ${level} config? This cannot be undone.`
    )
    if (confirmChoice !== 'yes') {
      this.interactor.displayText('Delete operation cancelled.')
      return context
    }

    // Delete provider
    await this.services.aiConfig.deleteProvider(chosenName, level)
    this.interactor.displayText(`✅ Provider '${chosenName}' deleted from ${level} config.`)
    return context
  }
}
