import { Interactor } from '@coday/model/interactor'
import { CommandHandler, CommandHandlerConstructorInput } from './command-handler'
import { CommandContext } from './command-context'
import { keywords } from '@coday/model/keywords'

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

    // If no handler found and subcommand is not empty, show help
    if (subCommand.trim().length > 0) {
      this.displayHelp(subCommand)
      return context
    }

    // If subcommand is empty, show interactive selection
    return this.handleInteractiveSelection(context)
  }

  /**
   * Handles interactive selection of a subhandler
   */
  protected async handleInteractiveSelection(context: CommandContext): Promise<CommandContext> {
    // Build the help text as a single string
    const helpText = this.buildHelpText()
    this.interactor.displayText(helpText)

    // Create a map of display options to command words
    const visibleHandlers = this.handlers.filter((h) => !h.isInternal)
    const optionsMap = new Map<string, string>()

    const options = visibleHandlers.map((h) => {
      // Create a display option with command word and short description
      const firstLineDescription = h.description.split('\n')[0]
      const displayOption = `${h.commandWord}: ${firstLineDescription}`
      optionsMap.set(displayOption, h.commandWord)
      return displayOption
    })

    // Add exit option
    options.push(`${keywords.exit}: Cancel and return`)
    optionsMap.set(`${keywords.exit}: Cancel and return`, keywords.exit)

    // Ask the user to select a subhandler
    const selection = await this.interactor.chooseOption(options, 'Select a command:', 'Enter your choice')

    // Get the actual command word from the selection
    const selectedCommandWord = optionsMap.get(selection)

    if (!selectedCommandWord || selectedCommandWord === keywords.exit) {
      return context
    }

    // pass on the request to the child handler
    return this.handle(`${this.commandWord} ${selectedCommandWord}`, context)
  }

  /**
   * Builds a complete help text string
   */
  protected buildHelpText(subCommand?: string): string {
    const lines: string[] = []

    if (subCommand) {
      lines.push(`Sub-command '${subCommand}' not understood.`)
    }

    lines.push('Available commands:')

    // Sort handlers alphabetically by command word
    const sortedHandlers = [...this.handlers]
      .filter((h) => !h.isInternal)
      .sort((a, b) => a.commandWord.localeCompare(b.commandWord))

    // Add each handler's description
    sortedHandlers.forEach((h) => {
      // Format the description with proper indentation for multi-line descriptions
      const formattedDescription = h.description
        .split('\n')
        .map((line, index) => (index === 0 ? line : `      ${line}`))
        .join('\n')

      lines.push(`  - ${h.commandWord}: ${formattedDescription}`)
    })

    return lines.join('\n')
  }

  displayHelp(subCommand?: string): void {
    const helpText = this.buildHelpText(subCommand)
    this.interactor.displayText(helpText)
  }
}
