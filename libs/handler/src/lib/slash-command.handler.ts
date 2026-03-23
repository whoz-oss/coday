import { NestedHandler } from './nested.handler'
import { Interactor, BuiltinPrompts } from '@coday/model'
import { PromptService } from '@coday/service'
import { PromptHandler } from './prompt.handler'
import { CommandHandler } from './command-handler'

const builtinPromptInfos = BuiltinPrompts.map((p) => ({
  id: p.id,
  name: p.name,
  description: p.description,
}))

/**
 * SlashCommandHandler - Routes slash commands to prompt handlers
 *
 * Intercepts commands starting with "/" and routes them to the appropriate
 * PromptHandler instance. Each prompt becomes a slash command.
 *
 * Examples:
 * - /pr-review 1234
 * - /deploy app=coday env=staging
 * - /analyze-logs error
 *
 * Custom handlers can be injected and take priority over prompt handlers.
 * They appear in the autocomplete via PromptService.list() stubs.
 *
 * This handler is initialized once per thread and loads all available prompts
 * at initialization time. No dynamic refresh - prompts are loaded when the
 * thread is created.
 */
export class SlashCommandHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    promptService: PromptService,
    projectName: string,
    prompts: Array<{ id: string; name: string; description: string }>,
    customHandlers: CommandHandler[] = []
  ) {
    super(
      {
        commandWord: '/',
        description: 'Execute slash commands (prompts)',
      },
      interactor
    )

    const allPrompts = [...builtinPromptInfos, ...prompts]

    // Names covered by custom handlers — no PromptHandler should be created for these
    const customHandlerNames = new Set(customHandlers.map((h) => h.commandWord.toLowerCase()))

    // Custom handlers take priority: they are checked first.
    // Prompt handlers follow for everything else (excluding names claimed by custom handlers).
    this.handlers = [
      ...customHandlers,
      ...allPrompts
        .filter((prompt) => !customHandlerNames.has(prompt.name.toLowerCase()))
        .map((prompt) => new PromptHandler(promptService, projectName, prompt.id, prompt.name, prompt.description)),
    ]
  }
}
