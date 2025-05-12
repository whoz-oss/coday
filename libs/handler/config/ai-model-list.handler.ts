import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'
import { ConfigLevel } from '../../model/config-level'

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
    // Gather configs
    const mergedConfig = this.services.aiConfig?.getMergedConfiguration()
    const codayProviders = this.services.aiConfig?.getProviders(ConfigLevel.CODAY) || []
    const projectProviders = this.services.aiConfig?.getProviders(ConfigLevel.PROJECT) || []
    const userProviders = this.services.aiConfig?.getProviders(ConfigLevel.USER) || []

    function formatModel(m: any): string {
      return `    - ${m.name}` + (m.alias ? ` (alias: ${m.alias})` : '') + (m.price ? ` [price info]` : '')
    }

    function section(title: string, providers: any[]) {
      let s = `${title}\n`
      if (!providers.length) return s + '(none)\n'
      providers.forEach((p) => {
        s += `  Provider: ${p.name}\n`
        if (Array.isArray(p.models) && p.models.length) s += p.models.map(formatModel).join('\n') + '\n'
        else s += '    (no models)\n'
      })
      return s
    }

    let out = ''
    out += section('--- coday.yaml (global, base) ---', codayProviders)
    out += '\n' + section('--- Project-level (.coday.yml) ---', projectProviders)
    out += '\n' + section('--- User-level (user.yml) ---', userProviders)
    // Merged
    out += '\n--- Merged client view (final) ---\n'
    if (!mergedConfig?.providers?.length) out += '(none)\n'
    else
      mergedConfig?.providers?.forEach((p) => {
        out += `  Provider: ${p.name}\n`
        if (Array.isArray(p.models) && p.models.length) out += p.models.map(formatModel).join('\n') + '\n'
        else out += '    (no models)\n'
      })

    this.interactor.displayText(out)
    return context
  }
}
