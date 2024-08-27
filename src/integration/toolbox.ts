import {CommandContext, Interactor, ToolRequestEvent} from "../model"
import {AiTools} from "./openai/ai.tools"
import {FileTools} from "./file/file.tools"
import {JiraTools} from "./jira/jira.tools"
import {GitTools} from "./git/git.tools"
import {ScriptsTools} from "./scripts.tools"
import {GitLabTools} from "./gitlab/gitlab.tools"
import {MemoryTools} from "./memory.tools"
import {AssistantToolFactory, Tool} from "./assistant-tool-factory"
import {filter} from "rxjs"
import {ConfluenceTools} from "./confluence/confluence.tools"

export class Toolbox {
  private toolFactories: AssistantToolFactory[]
  private tools: Tool[] = []
  
  constructor(interactor: Interactor) {
    this.toolFactories = [
      new AiTools(interactor),
      new FileTools(interactor),
      new JiraTools(interactor),
      new GitTools(interactor),
      new ScriptsTools(interactor),
      new GitLabTools(interactor),
      new MemoryTools(interactor),
      new ConfluenceTools(interactor)
    ]
    
    interactor.events.pipe(
      filter(e => e instanceof ToolRequestEvent),
    ).subscribe(async (toolRequest) => this.runTool(toolRequest, interactor))
  }
  
  getTools(context: CommandContext): Tool[] {
    this.tools = this.toolFactories.flatMap((factory) => factory.getTools(context))
    return this.tools
  }
  
  private async runTool(toolRequest: ToolRequestEvent, interactor: Interactor): Promise<void> {
    let output
    
    // run tools here from lastTools
    const funcWrapper = this.tools.find(
      (t) => t.function.name === toolRequest.name,
    )
    if (!funcWrapper) {
      output = `Function ${toolRequest.name} not found.`
      interactor.sendEvent(toolRequest.buildResponse(output))
      return
    }
    
    const toolFunc = funcWrapper.function.function
    
    try {
      let args: any = JSON.parse(toolRequest.args)
      
      if (!Array.isArray(args)) {
        args = [args]
      }
      output = await toolFunc.apply(null, args)
    } catch (err) {
      interactor.error(err)
      output = `Error on executing function, got error: ${JSON.stringify(err)}`
    }
    
    if (!output) {
      output = `Tool function ${funcWrapper.function.name} finished without error.`
    }
    
    if (typeof output !== "string") {
      output = JSON.stringify(output)
    }
    interactor.sendEvent(toolRequest.buildResponse(output))
  }
}