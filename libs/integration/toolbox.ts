import { CommandContext, Interactor } from '../model'
import { AiTools } from './ai/ai.tools'
import { FileTools } from './file/file.tools'
import { JiraTools } from './jira/jira.tools'
import { GitTools } from './git/git.tools'
import { ScriptsTools } from './scripts.tools'
import { GitLabTools } from './gitlab/gitlab.tools'
import { MemoryTools } from './memory.tools'
import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { ConfluenceTools } from './confluence/confluence.tools'

export class Toolbox {
  private toolFactories: AssistantToolFactory[]
  private tools: CodayTool[] = []

  constructor(interactor: Interactor) {
    this.toolFactories = [
      new AiTools(interactor),
      new FileTools(interactor),
      new JiraTools(interactor),
      new GitTools(interactor),
      new ScriptsTools(interactor),
      new GitLabTools(interactor),
      new MemoryTools(interactor),
      new ConfluenceTools(interactor),
    ]
  }

  getTools(context: CommandContext): CodayTool[] {
    this.tools = this.toolFactories.flatMap((factory) => factory.getTools(context))
    return this.tools
  }
}
