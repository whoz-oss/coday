import {CommandContext, Interactor} from "../model"
import {AiTools} from "./openai/ai-tools"
import {FileTools} from "./file/file.tools"
import {JiraTools} from "./jira/jira.tools"
import {GitTools} from "./git/git.tools"
import {ScriptsTools} from "./scripts.tools"
import {GitLabTools} from "./gitlab/gitlab.tools"
import {AssistantToolFactory, Tool} from "./assistant-tool-factory"

export class AllTools {
  private toolFactories: AssistantToolFactory[]
  
  constructor(interactor: Interactor) {
    this.toolFactories = [
      new AiTools(interactor),
      new FileTools(interactor),
      new JiraTools(interactor),
      new GitTools(interactor),
      new ScriptsTools(interactor),
      new GitLabTools(interactor),
    ]
  }
  
  getTools(context: CommandContext): Tool[] {
    return this.toolFactories.flatMap((factory) => factory.getTools(context))
  }
}