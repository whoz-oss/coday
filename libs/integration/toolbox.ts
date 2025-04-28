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
import { McpToolsFactory } from './mcp/mcp-tools-factory'
import { Killable } from '../model/killable'

export class Toolbox implements Killable {
  private toolFactories: AssistantToolFactory[]
  private tools: CodayTool[] = []

  constructor(
    private interactor: Interactor,
    services: CodayServices,
    agentService: AgentService
  ) {
    this.toolFactories = [
      new AiTools(interactor, agentService),
      new FileTools(interactor),
      new GitTools(interactor, services.integration),
      new ProjectScriptsTools(interactor),
      new GitLabTools(interactor, services.integration),
      new MemoryTools(interactor, services.memory),
      new ConfluenceTools(interactor, services.integration),
      new JiraTools(interactor, services.integration),
      ...services.mcp.getAllServers().map((serverConfig) => new McpToolsFactory(interactor, serverConfig)),
    ]
  }

  async kill(): Promise<void> {
    console.log(`Closing all toolFactories`)
    await Promise.all(this.toolFactories.map((f) => f.kill()))
    console.log(`Closed all toolFactories`)
  }

  async getTools(input: GetToolsInput): Promise<CodayTool[]> {
    const { context, integrations, agentName } = input

    // Filter factories based on integrations
    const filteredFactories = this.toolFactories.filter((factory) => !integrations || integrations.has(factory.name))

    try {
      // Process each filtered factory to get their tools
      const toolResults = await Promise.all(
        filteredFactories.map(async (factory) => {
          try {
            const tools = await factory.getTools(context, integrations?.get(factory.name) ?? [], agentName ?? 'default')
            return tools
          } catch (error) {
            this.interactor.error(`Error building tools from ${factory.name} for agent ${agentName}: ${error}`)
            // Return empty array if a specific factory fails
            return []
          }
        })
      )

      this.tools = toolResults.flat()
      return this.tools
    } catch (error) {
      this.interactor.error(`Unexpected error building tools for agent ${agentName}: ${error}`)
      // Return empty array in case of critical failure
      return []
    }
  }
}
