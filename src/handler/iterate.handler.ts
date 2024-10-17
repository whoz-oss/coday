import {AiClient, CommandContext, CommandHandler, Interactor} from "../model"
import {AiHandler} from "./openai/ai.handler"

export class IterateHandler extends CommandHandler {
  private handlers: CommandHandler[] = []
  
  constructor(
    private interactor: Interactor,
    private aiHandler: AiHandler,
    private aiClient: AiClient | undefined,
    private additionalHandlers: CommandHandler[] = [],
    private maxIterations: number = 100
  ) {
    super({
      commandWord: "iterate",
      description: "Executes iterative tasks by prompting and managing defined task strategies",
      requiredIntegrations: ["AI"],
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    this.handlers = [...this.additionalHandlers, this.aiHandler]
    
    if (!context) {
      throw new Error("Invalid command context")
    }
    
    let count = 0
    const initialCommand = this.getSubCommand(command)
    const nextWorkPrompt = this.assessAndDefineNextTask(initialCommand)
    console.log(`Starting iteration process...`, nextWorkPrompt)
    
    // TODO: context should have its command queue saved and temporarily replaced by an empty one, to let the exhaustion work properly.
    let loopContext = context.cloneWithoutCommands()
    
    while (count < this.maxIterations) {
      // Step 1: Prompt for next task
      
      await this.aiClient!.answer("coday", nextWorkPrompt, loopContext)
      
      let nextCommand = loopContext.getFirstCommand()
      if (!nextCommand) {
        this.interactor.displayText("No further tasks defined, iterate completed.")
        break
      }
      
      // Step 2: Execute the defined tasks
      while (nextCommand) {
        const handler: CommandHandler | undefined = this.handlers.find((h) =>
          h.accept(nextCommand!, loopContext)
        )
        
        try {
          if (handler) {
            loopContext = await handler.handle(nextCommand, loopContext)
          } else {
            this.interactor.error(`Could not handle request ${nextCommand}, check your AI integration`)
          }
          nextCommand = loopContext.getFirstCommand()
        } catch (error) {
          this.interactor.error(
            `An error occurred while processing your request: ${error}`
          )
        }
      }
      
      count++
    }
    
    if (count >= this.maxIterations) {
      this.interactor.warn("Maximum iterations reached, process stopped.")
    }
    
    return context
  }
  
  private assessAndDefineNextTask(task: string): string {
    return `## Iteration assessment
    
    I gave you the following initial task, to make progress iteratively by defining each time the next steps to delegate
    
    ### Initial task
    
    ${task}
    
    
    ### Progress assessment
    
    Given the past work, assess if the initial task is completed. If you deem it so, then do nothing more, use no functions and summarize the work done.
    
    
    ### Defining next step (if any)
    
    Otherwise, determine in light of the last steps, what should be done next.
    
    It can be for example :
    
    - an analysis of the initial task by extracting keywords and searching with the available tools
    - the logical next step according to an initial plan
    - another investigation if the initial analysis is proven wrong after the first steps
    - an exploration of option(s) to weight the pros and cons
    - another attempt of the previous step with better directions and clearer expectations
    
    It should not be :
    
    - a step unrelated to the initial task
    - a plain retry of a previously unsatisfying step execution
    - an overly complex step doing too much in one go (eg: renaming a reference across dozens of files)
    - a too simplistic and basic step that could be merged in a more significant one (eg: editing one line of code just before re-editing the full block)
    
   
    Prepare for this a single next step an description with
    
    - expectations
    - boundaries
    - useful tools
    
    Send this next step definition to the 'subTask' tool, do not attempt to complete in this run the task you defined.
   `
  }
}
