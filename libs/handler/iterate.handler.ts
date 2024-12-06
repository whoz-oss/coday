import { CommandContext, CommandHandler, Interactor } from '../model'
import { AiHandler } from './openai/ai.handler'

/**
 * FIXME: Handler not applicable anymore as functionality should be embedded inside ai.handler
 */
export class IterateHandler extends CommandHandler {
  private handlers: CommandHandler[] = []

  constructor(
    private interactor: Interactor,
    private aiHandler: AiHandler,
    private additionalHandlers: CommandHandler[] = [],
    private maxIterations: number = 100
  ) {
    super({
      commandWord: 'iterate',
      description: 'Executes iterative tasks by prompting and managing defined task strategies',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    this.handlers = [...this.additionalHandlers, this.aiHandler]

    if (!context) {
      throw new Error('Invalid command context')
    }

    let count = 0

    // TODO: context should have its command queue saved and temporarily replaced by an empty one, to let the exhaustion work properly.
    let loopContext = context.cloneWithoutCommands()

    while (count < this.maxIterations) {
      // Step 1: Prompt for next task

      // FIXME: not applicable anymore at handler level
      //await this.aiClient!.answer("coday", nextWorkPrompt, loopContext)

      let nextCommand = loopContext.getFirstCommand()
      if (!nextCommand) {
        this.interactor.displayText('No further tasks defined, iterate completed.')
        break
      }

      // Step 2: Execute the defined tasks
      while (nextCommand) {
        const handler: CommandHandler | undefined = this.handlers.find((h) => h.accept(nextCommand!, loopContext))

        try {
          if (handler) {
            loopContext = await handler.handle(nextCommand, loopContext)
          } else {
            this.interactor.error(`Could not handle request ${nextCommand}, check your AI integration`)
          }
          nextCommand = loopContext.getFirstCommand()
        } catch (error) {
          this.interactor.error(`An error occurred while processing your request: ${error}`)
        }
      }

      count++
    }

    if (count >= this.maxIterations) {
      this.interactor.warn('Maximum iterations reached, process stopped.')
    }

    return context
  }
}
