import {
  AddMessageHandler,
  CodeFlowHandler,
  CommandHandler,
  ConfigHandler,
  DebugHandler,
  GitHandler,
  OpenaiHandler,
  RunBashHandler,
  SubTaskHandler
} from "./handler"
import {Interactor} from "./model/interactor"
import {CommandContext} from "./model/command-context"
import {SmallTaskFlowHandler} from "./handler/small-task-flow.handler"
import {configService} from "./service/config-service"
import {keywords} from "./keywords"
import {GitlabReviewHandler} from "./handler/gitlab-review.handler"

const MAX_ITERATIONS = 100

export class HandlerLooper {
  private handlers: CommandHandler[] = []
  private maxIterations: number = MAX_ITERATIONS
  
  constructor(
    private interactor: Interactor,
    private openaiHandler: OpenaiHandler,
  ) {
  }
  
  init(username: string) {
    try {
      this.handlers = [
        new ConfigHandler(this.interactor, username),
        new GitHandler(this.interactor),
        new RunBashHandler(this.interactor),
        new DebugHandler(this.interactor),
        new CodeFlowHandler(),
        new SmallTaskFlowHandler(),
        new SubTaskHandler(this.interactor),
        new AddMessageHandler(this.interactor, this.openaiHandler.openaiClient),
        new GitlabReviewHandler(),
        this.openaiHandler
      ].filter(handler =>
        handler.requiredIntegrations.every(requiredIntegration => configService.hasIntegration(requiredIntegration))
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
      currentCommand = context.getFirstCommand()
      count++
      if (this.isHelpAsked(currentCommand)) {
        this.interactor.displayText("Available commands:")
        const handlerHelpMessages = [
          this.formatHelp("help/h/[nothing]", "displays this help message"),
          ...this.handlers
            .slice().filter(h => !h.isInternal).map(h => this.formatHelp(h.commandWord, h.description)).sort(),
          this.formatHelp("[any other text]", "defaults to asking the AI with the current context."),
          this.formatHelp(`${keywords.reset}`, "resets Coday's context."),
          this.formatHelp(`${keywords.exit}`, "quits the program."),
        ]
        handlerHelpMessages.forEach(msg => this.interactor.displayText(msg))
        
        currentCommand = undefined
        continue
      }
      if (!currentCommand) {
        continue
      }
      
      // find first handler
      const handler: CommandHandler | undefined = this.handlers.find((h) =>
        h.accept(currentCommand!, context),
      )
      
      try {
        if (handler) {
          context = await handler.handle(currentCommand, context)
        } else {
          // default case: repackage the command as an open question for AI
          context.addCommands(
            `${this.openaiHandler.commandWord} ${currentCommand}`,
          )
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
}
