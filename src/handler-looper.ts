import {AiClient, CommandContext, CommandHandler, Interactor, ProjectDescription, PromptChain} from "./model"
import {
  AddQueryHandler,
  AiHandler,
  CodayPromptChains,
  CodeFlowHandler,
  ConfigHandler,
  DebugHandler,
  FileMapHandler,
  GitHandler,
  GitlabReviewHandler,
  LoadHandler,
  MemoryHandler,
  PromptChainHandler,
  RunBashHandler,
  SubTaskHandler,
  ThreadHandler
} from "./handler"
import {integrationService} from "./service/integration.service"
import {keywords} from "./keywords"
import {OpenaiClient} from "./handler/openai.client"
import {DelegateHandler} from "./handler/delegate.handler"

const MAX_ITERATIONS = 100

export class HandlerLooper {
  private handlers: CommandHandler[] = []
  
  private maxIterations: number = MAX_ITERATIONS
  private killed: boolean = false
  
  constructor(
    private interactor: Interactor,
    private aiHandler: AiHandler,
    private aiClient: AiClient | undefined
  ) {
  }
  
  init(username: string, projectDescription: ProjectDescription | null) {
    try {
      const subTaskHandler = new SubTaskHandler(this.interactor)
      const queryHandler = new AddQueryHandler(this.interactor)
      const memoryHandler = new MemoryHandler(this.interactor)
      this.handlers = [
        new ConfigHandler(this.interactor, username),
        new GitHandler(this.interactor),
        new RunBashHandler(this.interactor),
        new DebugHandler(this.interactor),
        new CodeFlowHandler(),
        subTaskHandler,
        queryHandler,
        new GitlabReviewHandler(),
        memoryHandler,
        new DelegateHandler(this.interactor, this.aiHandler, this.aiClient, [
          subTaskHandler, queryHandler, memoryHandler
        ])
      ]
      if (this.aiClient) {
        this.handlers.push(
          new FileMapHandler(this.interactor, this.aiClient),
          new LoadHandler(this.interactor, this.aiClient),
        )
      }
      // FIXME: move thread management to AiClient
      if (integrationService.hasIntegration("OPENAI") && this.aiClient instanceof OpenaiClient) {
        this.handlers.push(
          new ThreadHandler(this.interactor, this.aiClient),
        )
      }
      
      CodayPromptChains.forEach(
        promptChain => this.handlers.push(
          new PromptChainHandler(
            this.interactor,
            promptChain,
            promptChain.name
          )
        )
      )
      
      if (projectDescription?.prompts) {
        for (const [promptName, promptChain] of Object.entries(projectDescription.prompts)) {
          this.handlers.push(
            new PromptChainHandler(
              this.interactor,
              promptChain as PromptChain,
              promptName
            )
          )
        }
      }
      
      // Add aiHandler at the end
      this.handlers.push(this.aiHandler)
      
      // Apply filtering based on required integrations
      this.handlers = this.handlers.filter(handler =>
        handler.requiredIntegrations.every(
          requiredIntegration => integrationService.hasIntegration(requiredIntegration)
        )
      )
    } catch (error) {
      this.interactor.error(`Error initializing handlers: ${error}`)
    }
  }
  
  async handle(context: CommandContext): Promise<CommandContext> {
    if (!context) {
      throw new Error("Invalid command context")
    }
    let count = 0
    let currentCommand: string | undefined
    do {
      if (this.killed) {
        return context
      }
      currentCommand = context.getFirstCommand()
      count++
      if (this.isHelpAsked(currentCommand)) {
        this.interactor.displayText("Available commands:")
        const handlerHelpMessages = [
          this.formatHelp("help/h/[nothing]", "displays this help message"),
          ...this.handlers
            .slice()
            .filter(h => !h.isInternal)
            .map(h => this.formatHelp(h.commandWord, h.description))
            .sort(),
          this.formatHelp("[any other text]", "defaults to asking the AI with the current context."),
          this.formatHelp(keywords.reset, "resets Coday's context."),
          this.formatHelp(keywords.exit, "quits the program."),
        ]
        handlerHelpMessages.forEach(msg => this.interactor.displayText(msg))
        continue
      }
      if (!currentCommand) {
        break
      }
      
      // find first handler
      const handler: CommandHandler | undefined = this.handlers.find((h) =>
        h.accept(currentCommand!, context),
      )
      
      try {
        if (handler) {
          context = await handler.handle(currentCommand, context)
        } else {
          if (!currentCommand.startsWith(this.aiHandler.commandWord)) {
            // default case: repackage the command as an open question for AI
            context.addCommands(
              `${this.aiHandler.commandWord} ${currentCommand}`,
            )
          } else {
            this.interactor.error(`Could not handle request ${currentCommand}, check your AI integration`)
          }
        }
      } catch (error) {
        this.interactor.error(
          `An error occurred while trying to process your request: ${error}`,
        )
      }
    } while (
      !!currentCommand || count < this.maxIterations
      )
    if (count >= this.maxIterations) {
      this.interactor.warn("Maximum iterations reached for a command")
    }
    return context
  }
  
  private isHelpAsked(command: string | undefined): boolean {
    return command === "" || command === "help" || command === "h"
  }
  
  private formatHelp(commandWord: string, description: string): string {
    const displayCommandWord = commandWord.padEnd(15, " ")
    return `  - ${displayCommandWord} : ${description}`
  }
  
  kill() {
    this.killed = true
    this.aiHandler.kill()
  }
}
