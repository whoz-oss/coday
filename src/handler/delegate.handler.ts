import {AiClient, CommandContext, CommandHandler, DEFAULT_DESCRIPTION, Interactor} from "../model"
import {AiHandler} from "./openai/ai.handler"

export class DelegateHandler extends CommandHandler {
  private handlers: CommandHandler[] = []
  private killed: boolean = false
  
  constructor(
    private interactor: Interactor,
    private aiHandler: AiHandler,
    private aiClient: AiClient | undefined,
    private additionalHandlers: CommandHandler[] = [],
    private maxIterations: number = 100
  ) {
    super({
      commandWord: "delegate",
      description: "runs the given prompt in another AI thread and returns a summarized report of the result",
      requiredIntegrations: ["AI"],
      isInternal: true
    })
  }
  
  async handle(command: string, parentContext: CommandContext): Promise<CommandContext> {
    this.handlers = [
      ...this.additionalHandlers,
      this.aiHandler
    ]
    
    if (!parentContext) {
      throw new Error("Invalid command context")
    }
    let count = 0
    let currentCommand: string | undefined
    const task = this.getSubCommand(command)
    let context = parentContext.getSubContext(task)
    do {
      if (this.killed) {
        // TODO: return something about being aborted ?
        return parentContext
      }
      currentCommand = context.getFirstCommand()
      count++
      
      // TODO: breakdown this big chunk and communalize with handler-looper
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
    let report = ""
    if (count >= this.maxIterations) {
      report = "Maximum iterations reached for a command, could not complete the task"
      this.interactor.warn(report)
    } else {
      // Do post-task actions
      report = await this.aiClient!.answer(DEFAULT_DESCRIPTION.name, reportPrompt(task), context)
    }
    
    context.addCommands(resumeWork(task, report))
    
    return context
  }
}

// TODO: move these functions outside for sharing with coday prompt chains
function reportPrompt(task: string): string {
  return `## Initial task
  
  ${task}
  
  ## Report
  
  Return me a report, and only a report, indicating in markdown:
  
  - completion status of the task: done, failed or other direct appreciation of the work done.
  - comments on completion: explain what went wrong or difficulties encountered or what is remaining
  - possible next steps: quick suggestions adhering to the spirit and topic of the task`
}

function resumeWork(task: string, report: string): string {
  return `## Delegated task
    
${task}

## Report on work done

${report}

## Assess report

Check quickly the report is consistent with the state of the project.

## Next step

Given the past work, assess if my initial request is completed. If you deem my initial request completed, then do nothing more.

Otherwise, determine in light of the last steps, what should be done next.

Prepare for this a single task description for the very next action:

- explain quickly the context of the task
- detail what is expected at a high level
- be directive but shy of mandating a solution

Then delegate this task by using the delegate function provided.
I'll re-contact you when the results are in.`
}


