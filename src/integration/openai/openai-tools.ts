import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {Interactor} from "../../model/interactor"
import {Beta} from "openai/resources"
import {CommandContext} from "../../model/command-context"
import {AssistantToolFactory, Tool} from "../../model/assistant-tool-factory"
import AssistantTool = Beta.AssistantTool

export class OpenaiTools extends AssistantToolFactory {
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return true
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    context.canSubTask(() => {
      const subTask = ({subTasks}: { subTasks: { description: string }[] }) => {
        subTasks.forEach(
          subTask => this.interactor.displayText(`Sub-task received: ${subTask.description}`)
        )
        if (context.addSubTasks(...subTasks.map(subTask => subTask.description))) {
          return "sub-tasks received and queued for execution, will be runned after this current run."
        }
        return "sub-tasks could not be queued, no more sub-tasking allowed for now."
      }
      const subTaskTool: AssistantTool & RunnableToolFunction<{ subTasks: { description: string }[] }> = {
        type: "function",
        function: {
          name: "subTask",
          description: "Queue tasks that will be runned sequentially after the current run. DO NOT TRY TO COMPLETE THESE TASKS, JUST DEFINE THEM HERE.",
          parameters: {
            type: "object",
            properties: {
              subTasks: {
                type: "array",
                description: "Ordered list of sub-tasks",
                items: {
                  type: "object",
                  properties: {
                    description: {
                      type: "string",
                      description: "Description of the sub-task, add details on what is specific to this task and what are the expectations on its completion."
                    }
                  }
                }
              }
            }
          },
          parse: JSON.parse,
          function: subTask
        }
      }
      result.push(subTaskTool)
    })
    
    if (!context.oneshot) {
      const queryUser = ({message}: { message: string }) => {
        const command = `add-query ${message}`
        context.addCommands(command)
        return "Query successfully queued, user will maybe answer later."
      }
      
      const queryUserTool: AssistantTool & RunnableToolFunction<{ message: string }> = {
        type: "function",
        function: {
          name: "queryUser",
          description: "Queues asynchronously a query (question or request) for the user who may answer later, after this current run. IMPORTANT: Use this tool only when necessary, as it interrupts the flow of execution to seek user input.",
          parameters: {
            type: "object",
            properties: {
              message: {
                type: "string",
                description: "The query to be added to the queue for user answer."
              }
            }
          },
          parse: JSON.parse,
          function: queryUser
        }
      }
      result.push(queryUserTool)
    }
    
    return result
  }
}
