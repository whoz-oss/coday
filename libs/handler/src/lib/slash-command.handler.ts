import { NestedHandler } from './nested.handler'
import { Interactor } from '@coday/model'
import { PromptService } from '@coday/service'
import { PromptHandler } from './prompt.handler'

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
 * This handler is initialized once per thread and loads all available prompts
 * at initialization time. No dynamic refresh - prompts are loaded when the
 * thread is created.
 */
export class SlashCommandHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    promptService: PromptService,
    projectName: string,
    prompts: Array<{ id: string; name: string; description: string }>
  ) {
    super(
      {
        commandWord: '/',
        description: 'Execute slash commands (prompts)',
      },
      interactor
    )

    // Create a PromptHandler for each prompt
    this.handlers = prompts.map(
      (prompt) => new PromptHandler(promptService, projectName, prompt.id, prompt.name, prompt.description)
    )
  }
}
