import {CommandHandler, CommandHandlerConstructorInput} from "./command-handler"
import {Interactor} from "../model/interactor"
import {CommandContext} from "../model/command-context"

export abstract class NestedHandler extends CommandHandler {
  handlers: CommandHandler[] = []
  
  protected constructor(input: CommandHandlerConstructorInput, protected readonly interactor: Interactor) {
    super(input)
  }
  
  override accept(command: string, context: CommandContext): boolean {
    if (!super.accept(command, context)) {
      return false
    }
    
    const subCommand = this.getSubCommand(command)
    
    return !subCommand || !!this.handlers.find((h) => h.accept(subCommand, context))
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    
    if (this.isHelpAsked(subCommand)) {
      return context
    }
    
    const handler = this.handlers.find((h: CommandHandler) => h.accept(subCommand, context))
    
    if (handler) {
      return handler.handle(subCommand, context)
    }
    
    this.interactor.warn(`No handler found in ${this.commandWord} handler`)
    return context
  }
  
  isHelpAsked(command?: string): boolean {
    const help = command === "" || command === "help" || command === "h"
    if (help) {
      this.interactor.displayText("Available commands:")
      this.interactor.displayText("  - help/h/[nothing] : displays this help message")
      this.handlers
        .slice()
        .sort((a, b) => a.commandWord.localeCompare(b.commandWord))
        .forEach(h => this.interactor.displayText(`  - ${h.commandWord} : ${h.description}`))
    }
    return help
  }
}
