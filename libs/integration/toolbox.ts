import { Interactor } from '@coday/model'
import { AiTools } from './ai/ai.tools'
import { DelegateTools } from './ai/delegate.tools'
import { FileTools } from './file/file.tools'
import { JiraTools } from './jira/jira.tools'
import { GitTools } from './git/git.tools'
import { ProjectScriptsTools } from './projectScriptsTools'
import { GitLabTools } from './gitlab/gitlab.tools'
import { MemoryTools } from './memory.tools'
import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { ConfluenceTools } from './confluence/confluence.tools'
import { ZendeskTools } from './zendesk-articles/zendesk.tools'
import { CodayServices } from '../coday-services'
import { AgentService } from '@coday/agent'
import { GetToolsInput } from './types'
import { McpToolsFactory } from './mcp/mcp-tools-factory'
import { Killable } from '@coday/model'

export class Toolbox implements Killable {
  private readonly toolFactories: AssistantToolFactory[]
  private tools: CodayTool[] = []

  constructor(
    private readonly interactor: Interactor,
    services: CodayServices,
    agentService: AgentService
  ) {
    const mcps = services.mcp.getMergedConfiguration().servers
    this.toolFactories = [
      new AiTools(interactor, agentService),
      new DelegateTools(interactor, agentService),
      new FileTools(interactor),
      new GitTools(interactor, services.integration),
      new ProjectScriptsTools(interactor),
      new GitLabTools(interactor, services.integration),
      new MemoryTools(interactor, services.memory),
      new ConfluenceTools(interactor, services.integration),
      new ZendeskTools(interactor, services.integration),
      new JiraTools(interactor, services.integration),
      ...mcps.map((mcpConfig) => new McpToolsFactory(interactor, mcpConfig)),
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
            return await factory.getTools(context, integrations?.get(factory.name) ?? [], agentName ?? 'default')
          } catch (error) {
            this.interactor.debug(`Error building tools from ${factory.name} for agent ${agentName}: ${error}`)
            // Return empty array if a specific factory fails
            return []
          }
        })
      )

      this.tools = toolResults.flat()
      return this.tools
    } catch (error) {
      this.interactor.debug(`Unexpected error building tools for agent ${agentName}: ${error}`)
      // Return empty array in case of critical failure
      return []
    }
  }
}
