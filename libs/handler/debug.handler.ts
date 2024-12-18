import { CommandContext, CommandHandler } from '../model'

export class DebugHandler extends CommandHandler {
  constructor() {
    super({
      commandWord: 'debug',
      description: 'run a command for dev-testing purposes',
      isInternal: true,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    console.log('not doing much right now...')
    return context
  }
}
