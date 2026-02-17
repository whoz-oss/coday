import { CommandHandler } from './command-handler'
import { CommandContext, Prompt } from '@coday/model'
import { PromptService } from '@coday/service'
import { BuiltinPrompts } from './builtin-prompts'

/**
 * PromptHandler - Execute a single stored prompt
 *
 * Similar to PromptChainHandler but for prompts loaded from PromptService.
 * One handler instance per prompt, registered as a slash command.
 *
 * Usage:
 * - /pr-review 1234
 * - /deploy app=coday env=staging
 *
 * Each prompt becomes a slash command with its name as the command word.
 */
export class PromptHandler extends CommandHandler {
  constructor(
    private readonly promptService: PromptService,
    private readonly projectName: string,
    private readonly promptId: string,
    promptName: string,
    promptDescription: string
  ) {
    super({
      commandWord: promptName,
      description: promptDescription,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Split command at first space: promptName + arguments
    // Example: "impersona celebrity="napoleon" activity="golf""
    // -> promptName = "impersona"
    // -> userInput = "celebrity="napoleon" activity="golf""
    const firstSpaceIndex = command.indexOf(' ')
    const userInput = firstSpaceIndex === -1 ? '' : command.substring(firstSpaceIndex + 1).trim()

    // Load full prompt (check built-ins first, then user prompts)
    let fullPrompt: Prompt | null = BuiltinPrompts.find((p) => p.id === this.promptId) || null

    if (!fullPrompt) {
      fullPrompt = await this.promptService.get(this.projectName, this.promptId)
    }

    if (!fullPrompt || !fullPrompt.commands || fullPrompt.commands.length === 0) {
      throw new Error(`Prompt "${this.commandWord}" has no commands configured`)
    }

    // Parse parameters
    const parameters = this.parseParameters(userInput)

    // Process commands with parameter interpolation
    const processedCommands = this.processCommands(fullPrompt.commands, parameters)

    // Add commands to context
    context.addCommands(...processedCommands)

    return context
  }

  /**
   * Parse user input into parameters
   * Detects structured parameters (key=value) vs simple string
   * Supports quoted values: key="value with spaces" or key='value'
   */
  private parseParameters(userInput: string): Record<string, string> | string | undefined {
    if (!userInput) {
      return undefined
    }

    // Check if input contains key=value pairs (with optional quotes)
    // Matches: key="value" or key='value' or key=value
    const keyValuePattern = /(\w+)=(?:"([^"]*)"|'([^']*)'|([^\s]+))/g
    const matches = [...userInput.matchAll(keyValuePattern)]

    if (matches.length === 0) {
      // Simple string parameter (no key=value detected)
      return userInput
    }

    // Structured parameters
    const params: Record<string, string> = {}
    for (const match of matches) {
      const key = match[1]
      // Value can be in group 2 (double quotes), 3 (single quotes), or 4 (no quotes)
      const value = match[2] ?? match[3] ?? match[4]
      if (key && value !== undefined) {
        params[key] = value
      }
    }

    return params
  }

  /**
   * Process commands with parameter interpolation
   *
   * Rules (same as PromptExecutionService):
   * 1. String parameter:
   *    - If {{PARAMETERS}} present → replace in ALL commands
   *    - If no placeholders → append to FIRST command only
   *    - If other {{key}} placeholders → Error
   * 2. Object parameter:
   *    - Replace all {{key}} with values from object
   * 3. Undefined:
   *    - Commands used as-is
   */
  private processCommands(commands: string[], parameters: Record<string, string> | string | undefined): string[] {
    let processed: string[]

    if (typeof parameters === 'string') {
      // String parameter mode
      const hasParametersPlaceholder = commands.some((cmd) => /\{\{PARAMETERS\}\}/.test(cmd))
      const hasOtherPlaceholders = commands.some((cmd) => /\{\{(?!PARAMETERS\}\})\w+\}\}/.test(cmd))

      if (hasOtherPlaceholders) {
        throw new Error(
          `Prompt "${this.commandWord}" contains structured placeholders ({{key}}). Use key=value format: /${this.commandWord} key1=value1 key2=value2`
        )
      }

      if (hasParametersPlaceholder) {
        // Replace {{PARAMETERS}} in ALL commands
        processed = commands.map((cmd) => cmd.replace(/\{\{PARAMETERS\}\}/g, parameters))
      } else {
        // No placeholders → Append to first command only
        processed = commands.map((cmd, index) => (index === 0 ? `${cmd} ${parameters}`.trim() : cmd))
      }
    } else if (typeof parameters === 'object' && parameters !== null) {
      // Object parameter mode - structured interpolation
      processed = commands.map((command) => {
        let processedCommand = command
        Object.entries(parameters).forEach(([key, value]) => {
          const placeholder = `{{${key}}}`
          processedCommand = processedCommand.replace(new RegExp(placeholder, 'g'), value)
        })
        return processedCommand
      })
    } else {
      // No parameters - use commands as-is
      processed = [...commands]
    }

    // Final validation: check for remaining placeholders
    const remainingPlaceholders = new Set<string>()
    processed.forEach((cmd) => {
      const matches = cmd.match(/\{\{(\w+)\}\}/g)
      if (matches) {
        matches.forEach((match) => remainingPlaceholders.add(match))
      }
    })

    if (remainingPlaceholders.size > 0) {
      const missingKeys = Array.from(remainingPlaceholders)
        .map((p) => p.replace(/[{}]/g, ''))
        .join(', ')
      throw new Error(`Missing required parameters for /${this.commandWord}: ${missingKeys}`)
    }

    return processed
  }
}
