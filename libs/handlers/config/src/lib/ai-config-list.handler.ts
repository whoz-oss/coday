import { CommandContext, CommandHandler } from '@coday/handler'
import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model'

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

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    // Get merged and per-level configs
    const mergedConfig = this.services.aiConfig?.getMergedConfiguration()
    const codayProviders = this.services.aiConfig?.getProviders(ConfigLevel.CODAY) || []
    const projectProviders = this.services.aiConfig?.getProviders(ConfigLevel.PROJECT) || []
    const userProviders = this.services.aiConfig?.getProviders(ConfigLevel.USER) || []

    let output = 'AI Provider Configurations (all levels):\n\n'

    output += '--- coday.yaml (base/global) ---\n'
    if (codayProviders.length === 0) output += '(none)\n'
    else codayProviders.forEach((p) => (output += this.formatProvider(p) + '\n'))

    output += '\n--- Project-level (.coday.yml) ---\n'
    if (projectProviders.length === 0) output += '(none)\n'
    else projectProviders.forEach((p) => (output += this.formatProvider(p) + '\n'))

    output += '\n--- User-level (user.yml) ---\n'
    if (userProviders.length === 0) output += '(none)\n'
    else userProviders.forEach((p) => (output += this.formatProvider(p) + '\n'))

    output += '\n--- Merged client view (final precedence) ---\n'
    if (mergedConfig?.providers?.length === 0) output += '(none)\n'
    else mergedConfig?.providers?.forEach((p) => (output += this.formatProvider(p) + '\n'))

    this.interactor.displayText(output)
    return context
  }

  private formatProvider(p: any): string {
    // Name + type (if present) + url (if present) + model count
    return (
      `- ${p.name}` +
      (p.type ? ` [${p.type}]` : '') +
      (p.url ? ` url=${p.url}` : '') +
      (p.apiKey ? ' (apiKey set)' : '') +
      (p.secure ? ' [secure]' : '') +
      (p.models?.length ? `, models: ${p.models.length}` : '')
    )
  }
}
