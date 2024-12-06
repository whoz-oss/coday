import { CommandHandler, CommandHandlerConstructorInput } from './command.handler'
import { Interactor } from './interactor'
import { CommandContext } from './command-context'

export abstract class NestedHandler extends CommandHandler {
  protected handlers: CommandHandler[] = []

  protected constructor(
    input: CommandHandlerConstructorInput,
    protected readonly interactor: Interactor
  ) {
    super(input)
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)

    const handler = this.handlers.find((h: CommandHandler) => h.accept(subCommand, context))

    if (handler) {
      return handler.handle(subCommand, context)
    }
    this.displayHelp(subCommand)
    return context
  }

  displayHelp(subCommand?: string): void {
    if (subCommand) {
      this.interactor.displayText(`Sub-command '${subCommand}' not understood.`)
    }
    this.interactor.displayText('Available commands:')
    this.handlers
      .slice()
      .sort((a, b) => a.commandWord.localeCompare(b.commandWord))
      .forEach((h) => this.interactor.displayText(`  - ${h.commandWord} : ${h.description}`))
  }
}
