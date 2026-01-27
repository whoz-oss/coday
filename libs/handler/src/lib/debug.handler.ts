import { CommandHandler } from './command-handler'
import { Interactor } from '@coday/model'
import { CommandContext } from '@coday/model'

export class DebugHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'debug',
      description: 'allows to set the debug logs (ex: `debug true` or `debug false`). False by default.',
      isInternal: true,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const value = this.getSubCommand(command)

    if (value === 'true') {
      this.interactor.debugLevelEnabled = true
      this.interactor.displayText('Set debug at true')
    }
    if (value === 'false') {
      this.interactor.debugLevelEnabled = false
      this.interactor.displayText('Set debug at false')
    }

    return context
  }
}
