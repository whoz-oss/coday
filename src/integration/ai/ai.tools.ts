import {CommandContext, Interactor} from "../../model"
import {AssistantToolFactory, CodayTool} from "../assistant-tool-factory"
import {FunctionTool} from "../types"

export class AiTools extends AssistantToolFactory {
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return true
  }
  
  protected buildTools(context: CommandContext): CodayTool[] {
    const result: CodayTool[] = []
    context.canSubTask(() => {
      const subTask = ({subTasks}: { subTasks: { description: string }[] }) => {
        const formatted = subTasks.map(subTask => `@ ${subTask.description}`)
        formatted.forEach(
          command => this.interactor.displayText(`Sub-task received: ${command}`)
        )
        if (context.addSubTasks(...formatted)) {
          return "sub-tasks received and queued for execution, will be runned after this current run."
        }
        return "sub-tasks could not be queued, no more sub-tasking allowed for now."
      }
      const subTaskTool: FunctionTool<{ subTasks: { description: string }[] }> = {
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
    
    if (context.stackDepth > 0) {
      const delegate = ({task}: { task: string }) => {
        context.addCommands(`delegate ${task}`)
        return "Task delegated to another process."
      }
      
      const delegateTool: FunctionTool<{ task: string }> = {
        type: "function",
        function: {
          name: "delegate",
          description: `Delegate the completion of a task to another async process. Result will`,
          parameters: {
            type: "object",
            properties: {
              task: {
                type: "string",
                description: `Description of the task, expected to have the following structure :
                
                - context: a quick explanation of the parent context of the task
                - task: description of the task to complete, with rather clear expectations and boundaries, but no suggested solution
                - data: mention of files, piece of data or other constraints related to the task
                - tools: optional, recommended tools to use if **very** relevant to the task.`,
              }
            }
          },
          parse: JSON.parse,
          function: delegate
        }
      }
      result.push(delegateTool)
    }
    
    if (!context.oneshot) {
      const queryUser = ({message}: { message: string }) => {
        const command = `add-query ${message}`
        context.addCommands(command)
        return "Query successfully queued, user will maybe answer later."
      }
      
      const queryUserTool: FunctionTool<{ message: string }> = {
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
