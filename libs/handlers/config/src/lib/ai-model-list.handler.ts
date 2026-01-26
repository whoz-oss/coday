import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { ConfigLevel } from '@coday/model'

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
      description:
        'List models for AI provider configurations. Use --provider=name (optional), --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments
    const subCommand = this.getSubCommand(command)
    const args = parseArgs(subCommand, [{ key: 'provider' }, { key: 'project', alias: 'p' }])
    const isProject = !!args.project
    const providerName = typeof args.provider === 'string' ? args.provider : undefined

    if (isProject) {
      // Show only project level
      const level = ConfigLevel.PROJECT
      const providers = this.services.aiConfig?.getProviders(level) || []
      let filteredProviders = providerName ? providers.filter((p) => p.name === providerName) : providers

      this.displayProviders(`PROJECT level models:`, filteredProviders)
    } else {
      // Show all levels (original behavior)
      const mergedConfig = this.services.aiConfig?.getMergedConfiguration()
      const codayProviders = this.services.aiConfig?.getProviders(ConfigLevel.CODAY) || []
      const projectProviders = this.services.aiConfig?.getProviders(ConfigLevel.PROJECT) || []
      const userProviders = this.services.aiConfig?.getProviders(ConfigLevel.USER) || []

      // Filter by provider if specified
      const filterByProvider = (providers: any[]) =>
        providerName ? providers.filter((p) => p.name === providerName) : providers

      let out = ''
      out += this.section('--- coday.yaml (global, base) ---', filterByProvider(codayProviders))
      out += '\n' + this.section('--- Project-level (.coday.yml) ---', filterByProvider(projectProviders))
      out += '\n' + this.section('--- User-level (user.yml) ---', filterByProvider(userProviders))
      // Merged
      out += '\n--- Merged client view (final) ---\n'
      const mergedFiltered = filterByProvider(mergedConfig?.providers || [])
      if (!mergedFiltered.length) out += '(none)\n'
      else
        mergedFiltered.forEach((p) => {
          out += `  Provider: ${p.name}\n`
          if (Array.isArray(p.models) && p.models.length) out += p.models.map(this.formatModel).join('\n') + '\n'
          else out += '    (no models)\n'
        })

      this.interactor.displayText(out)
    }
    return context
  }

  private formatModel(m: any): string {
    return `    - ${m.name}` + (m.alias ? ` (alias: ${m.alias})` : '') + (m.price ? ` [price info]` : '')
  }

  private section(title: string, providers: any[]) {
    let s = `${title}\n`
    if (!providers.length) return s + '(none)\n'
    providers.forEach((p) => {
      s += `  Provider: ${p.name}\n`
      if (Array.isArray(p.models) && p.models.length) s += p.models.map(this.formatModel).join('\n') + '\n'
      else s += '    (no models)\n'
    })
    return s
  }

  private displayProviders(title: string, providers: any[]) {
    let out = this.section(title, providers)
    this.interactor.displayText(out)
  }
}
