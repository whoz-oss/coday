import {CommandContext} from "../model/command-context"
import {IntegrationName} from "../model/integration-name"
import {CommandHandler} from "../model/command.handler"

export class SmallTaskHandler extends CommandHandler {
  
  constructor() {
    super({
      commandWord: "small-task",
      description: "expand the request into a simple flow of requests : analysis, implementation, review. No delegation to other assistants is encouraged and no sub-tasking.",
      requiredIntegrations: [IntegrationName.OPENAI]
    })
  }
  
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const cmd = this.getSubCommand(command)
    
    const expertInstructions = [
      // PREPARATION
      // gather data and make a plan
      `sub-task false`, // prevent any sub-tasking
      
      `@ You are given this small task: ${cmd}
            
            As a first step, analyse the task, search for keywords, files or external references using available functions, search also for validation or review material, being documentation or existing tests.
            Do not start working on the solution, just gather data to get a more detailed and deeper understanding of the task.`,
      `add-query Was something missed in this analysis ?`,
      `@ Now build a sound and reasonable plan on how to complete the task. Don't get carried over, keep things simple and respect the spirit or flavor of the project.
            DO NOT EXECUTE THE PLAN NOW !`,
      `@ Then review your plan, search for improvements and weaknesses to cover, still in the spirit of the project.
            DO NOT EXECUTE THE PLAN NOW !`,
      
      `add-query Anything to add before executing this plan ?`,
      
      // EXECUTION
      `@ Execute the built plan, taking into account any previous message, and all project rules.
            Make sure to adjust the execution to any hiccups and still try to complete the task.`,
    ]
    
    context.addCommands(...expertInstructions)
    return context
  }
}
