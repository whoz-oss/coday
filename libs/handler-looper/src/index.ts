import { CommandContext, CommandHandler, Interactor, ProjectDescription, PromptChain } from './model'
import {
  AddQueryHandler,
  AiHandler,
  CodayPromptChains,
  ConfigHandler,
  DebugHandler,
  FileMapHandler,
  LoadHandler,
  MemoryHandler,
  PromptChainHandler,
  RunBashHandler,
} from './handler'
import { StatsHandler } from './handler/stats/stats.handler'
import { RunStatus } from './ai-thread/ai-thread.types'
import { CodayServices } from './coday-services'

const MAX_ITERATIONS = 100

export class HandlerLooper {
  private handlers: CommandHandler[] = []

  private maxIterations: number = MAX_ITERATIONS
  private killed: boolean = false
  private processing: boolean = false

  constructor(
    private interactor: Interactor,
    private aiHandler: AiHandler,
    private configHandler: ConfigHandler,
    private services: CodayServices // unused temporarily...
  ) {}

  init(projectDescription: ProjectDescription | null) {
    try {
      const queryHandler = new AddQueryHandler(this.interactor)
      const memoryHandler = new MemoryHandler(this.interactor, this.services)
      this.handlers = [
        this.configHandler,
        new RunBashHandler(this.interactor),
        new DebugHandler(this.interactor),
        queryHandler,
        memoryHandler,
        new FileMapHandler(this.interactor),
        new LoadHandler(this.interactor),
        new StatsHandler(this.interactor, this.services),
      ]

      CodayPromptChains.forEach((promptChain) =>
        this.handlers.push(new PromptChainHandler(promptChain, promptChain.name))
      )

      if (projectDescription?.prompts) {
        for (const [promptName, promptChain] of Object.entries(projectDescription.prompts)) {
          this.handlers.push(new PromptChainHandler(promptChain as PromptChain, promptName))
        }
      }

      // SUPER IMPORTANT: Add aiHandler at the end !!!!!!!!!!!!!
      this.handlers.push(this.aiHandler)

      // Apply filtering based on required integrations
      this.handlers = this.handlers.filter((handler) =>
        handler.requiredIntegrations.every((requiredIntegration) =>
          this.services.integration.hasIntegration(requiredIntegration)
        )
      )
    } catch (error) {
      this.interactor.error(`Error initializing handlers: ${error}`)
    }
  }

  async handle(context: CommandContext): Promise<CommandContext> {
    if (!context) {
      throw new Error('Invalid command context')
    }
    let count = 0
    let currentCommand: string | undefined
    this.processing = true
    try {
      do {
        if (this.killed) {
          return context
        }
        currentCommand = context.getFirstCommand()
        count++
        if (this.isHelpAsked(currentCommand)) {
          const handlerHelpMessages = [
            'Available commands:',
            this.formatHelp('help/h/[nothing]', 'displays this help message'),
            ...this.handlers
              .slice()
              .filter((h) => !h.isInternal)
              .map((h) => this.formatHelp(h.commandWord, h.description))
              .sort(),
            this.formatHelp('[any other text]', 'defaults to asking the AI with the current context.'),
          ]
          // Keep this as displayText since help output is explicitly for the user
          this.interactor.displayText(handlerHelpMessages.join('\n'))
          continue
        }
        if (!currentCommand) {
          break
        }

        // find first handler
        const handler: CommandHandler | undefined = this.handlers.find((h) => h.accept(currentCommand!, context))

        try {
          if (handler) {
            context = await handler.handle(currentCommand, context)
            // Check if thread was stopped during handler execution
            if (context.aiThread?.runStatus === RunStatus.STOPPED) {
              context.clearCommands()
              this.processing = false
              return context
            }
          } else {
            if (!currentCommand.startsWith(this.aiHandler.commandWord)) {
              // default case: repackage the command as an open question for AI
              context.addCommands(`${this.aiHandler.commandWord} ${currentCommand}`)
            } else {
              this.interactor.error(`Could not handle request ${currentCommand}, check your AI integration`)
            }
          }
        } catch (error) {
          this.interactor.error(`An error occurred while trying to process your request: ${error}`)
        }
      } while ((!!currentCommand || count < this.maxIterations) && this.processing)
    } finally {
      this.processing = false
    }
    if (count >= this.maxIterations) {
      this.interactor.warn('Maximum iterations reached for a command')
    }
    return context
  }

  private isHelpAsked(command: string | undefined): boolean {
    return command === '' || command === 'help' || command === 'h'
  }

  private formatHelp(commandWord: string, description: string): string {
    const displayCommandWord = commandWord.padEnd(15, ' ')
    return `  - ${displayCommandWord} : ${description}`
  }

  /**
   * Requests a graceful stop of the current AI processing.
   * - Preserves thread and context state
   * - Allows clean completion of current operation
   * - Prevents new processing steps
   *
   * @returns Promise that resolves when stop is complete
   */
  stop(): void {
    this.processing = false
  }

  /**
   * Immediately terminates all processing.
   * Unlike stop(), this method:
   * - Does not preserve state
   * - Immediately ends all processing
   * - May leave cleanup needed
   */
  kill() {
    this.stop()
    this.killed = true
    this.aiHandler.kill()
  }
}
