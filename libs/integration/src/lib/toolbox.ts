import { CodayServices } from '@coday/coday-services'
import { Killable } from '@coday/model/killable'
import { AssistantToolFactory } from '@coday/integration/assistant-tool-factory'
import { McpServerConfig } from '@coday/model/mcp-server-config'
import { CodayTool } from '@coday/model/coday-tool'
import { Interactor } from '@coday/model/interactor'
import { AgentService } from '@coday/agent'
import { CoreTools } from '@coday/integration/core.tools'
import { AiTools, DelegateTools } from '@coday/integrations/ai'
import { GitTools } from '@coday/integrations/git'
import { GitLabTools } from '@coday/integrations/gitlab'
import { ProjectScriptsTools } from '@coday/integration/projectScriptsTools'
import { MemoryTools } from '@coday/integration/memory.tools'
import { GetToolsInput } from '@coday/handler'
import { McpToolsFactory } from '@coday/mcp'
import { OAuthCallbackEvent } from '@coday/model/coday-events'
import { FileTools } from '@coday/integrations/file'
import { ConfluenceTools } from '@coday/integrations/confluence'
import { ZendeskTools } from '@coday/integrations/zendesk-articles'
import { JiraTools } from '@coday/integrations/jira'
import { SlackTools } from '@coday/integrations/slack'
import { BasecampTools } from '@coday/integrations/basecamp'

export class Toolbox implements Killable {
  private readonly toolFactories: AssistantToolFactory[]
  private readonly mcpConfigs: McpServerConfig[]
  private tools: CodayTool[] = []

  constructor(
    private readonly interactor: Interactor,
    private readonly services: CodayServices,
    agentService: AgentService
  ) {
    // Store MCP configs for lazy initialization via pool
    this.mcpConfigs = services.mcp.getMergedConfiguration().servers

    // Create non-MCP tool factories immediately
    this.toolFactories = [
      new CoreTools(interactor, services),
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
      new SlackTools(interactor, services.integration),
      new BasecampTools(interactor, services.integration, services.user),
    ]
  }

  async kill(): Promise<void> {
    console.log(`[TOOLBOX] Closing non-MCP tool factories`)
    await Promise.all(this.toolFactories.map((f) => f.kill()))
    console.log(`[TOOLBOX] Closed non-MCP tool factories`)

    // Note: MCP factories are managed by the pool
    // They will be released when ThreadCodayManager calls mcpPool.releaseThread()
  }

  async getTools(input: GetToolsInput): Promise<CodayTool[]> {
    const { context, integrations, agentName } = input

    // Get threadId from context
    const threadId = context.aiThread?.id
    if (!threadId) {
      this.interactor.warn('No thread ID in context, MCP tools will not be available')
    }

    // Get or create MCP factories via pool (lazy initialization)
    const mcpFactories: AssistantToolFactory[] = []
    if (threadId) {
      for (const mcpConfig of this.mcpConfigs) {
        try {
          const factory = await this.services.mcpPool.getOrCreateFactory(
            mcpConfig,
            threadId,
            () => new McpToolsFactory(mcpConfig)
          )
          mcpFactories.push(factory)
        } catch (error) {
          this.interactor.debug(`Error creating MCP factory for ${mcpConfig.name}: ${error}`)
          // Continue with other factories
        }
      }
    }

    // Combine non-MCP and MCP factories
    const allFactories = [...this.toolFactories, ...mcpFactories]

    // Filter factories based on integrations
    const filteredFactories = allFactories.filter((factory) => !integrations || integrations.has(factory.name))

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

  /**
   * Route OAuth callback events to the appropriate integration
   */
  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    // Find the factory for this integration
    const factory = this.toolFactories.find((f) => f.name === event.integrationName)

    if (!factory) {
      this.interactor.warn(`No integration found for OAuth callback: ${event.integrationName}`)
      return
    }

    // Check if the factory has a handleOAuthCallback method
    if ('handleOAuthCallback' in factory && typeof factory.handleOAuthCallback === 'function') {
      try {
        await factory.handleOAuthCallback(event)
      } catch (error) {
        this.interactor.error(`Error handling OAuth callback for ${event.integrationName}: ${error}`)
      }
    } else {
      this.interactor.warn(`Integration ${event.integrationName} does not support OAuth callbacks`)
    }
  }
}
