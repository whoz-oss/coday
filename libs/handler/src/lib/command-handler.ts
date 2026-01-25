import { CommandContext } from './command-context'

export type CommandHandlerConstructorInput = {
  commandWord: string
  description: string
  requiredIntegrations?: string[]
  isInternal?: boolean
}

export abstract class CommandHandler {
  commandWord: string
  description: string
  requiredIntegrations: string[] = []
  isInternal: boolean = false

  constructor(input: CommandHandlerConstructorInput) {
    this.commandWord = input.commandWord
    this.description = input.description
    this.requiredIntegrations = input.requiredIntegrations || []
    this.isInternal = !!input?.isInternal
  }

  getSubCommand(command: string): string {
    return command.slice(this.commandWord.length).trim()
  }

  accept(command: string, _context: CommandContext): boolean {
    return !!command && command.toLowerCase().startsWith(this.commandWord)
  }

  abstract handle(command: string, context: CommandContext): Promise<CommandContext>
}
