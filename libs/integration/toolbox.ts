import { Interactor } from '../model'
import { AiTools } from './ai/ai.tools'
import { FileTools } from './file/file.tools'
import { JiraTools } from './jira/jira.tools'
import { GitTools } from './git/git.tools'
import { ProjectScriptsTools } from './projectScriptsTools'
import { GitLabTools } from './gitlab/gitlab.tools'
import { MemoryTools } from './memory.tools'
import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { ConfluenceTools } from './confluence/confluence.tools'
import { CodayServices } from '../coday-services'
import { AgentService } from '../agent'
import { GetToolsInput } from './types'

export class Toolbox {
  private toolFactories: AssistantToolFactory[]
  private tools: CodayTool[] = []

  constructor(interactor: Interactor, services: CodayServices, agentService: AgentService) {
    this.toolFactories = [
      new AiTools(interactor, agentService),
      new FileTools(interactor),
      new GitTools(interactor, services.integration),
      new ProjectScriptsTools(interactor),
      new GitLabTools(interactor, services.integration),
      new MemoryTools(interactor, services.memory),
      new ConfluenceTools(interactor, services.integration),
      new JiraTools(interactor, services.integration),
    ]
  }

  async getTools(input: GetToolsInput): Promise<CodayTool[]> {
    const { context, integrations, agentName } = input
    const filteredFactories = this.toolFactories.filter((factory) => !integrations || integrations.has(factory.name))
    this.tools = (
      await Promise.all(
        filteredFactories.map((factory) => factory.getTools(context, integrations?.get(factory.name) ?? [], agentName))
      )
    ).flat()
    return this.tools
  }
}
