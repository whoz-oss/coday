import { Interactor } from '../model'
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
import { SlackTools } from './slack/slack.tools'
import { BasecampTools } from './basecamp/basecamp.tools'
import { CodayServices } from '../coday-services'
import { AgentService } from '../agent'
import { GetToolsInput } from './types'
import { McpToolsFactory } from './mcp/mcp-tools-factory'
import { McpServerConfig } from '@coday/model/mcp-server-config'
import { Killable } from '@coday/model'
import {
  OAuthCallbackEvent,
  GlobalAutoAcceptStateEvent,
  PerToolAutoAcceptStateEvent,
  PerToolAutoAcceptResetEvent,
} from '@coday/coday-events'

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

  /**
   * Get the current global auto-accept state from the interactor
   */
  public getGlobalAutoAcceptState(): boolean {
    return this.interactor.globalAutoAccept || false
  }

  /**
   * Toggle global auto-accept state and emit event
   * When enabled, skips ALL confirmations across all tools
   * When disabled, each tool maintains its own auto-accept state
   */
  public toggleGlobalAutoAccept(): void {
    const wasOn = this.interactor.globalAutoAccept
    this.interactor.globalAutoAccept = !this.interactor.globalAutoAccept

    this.interactor.displayText(
      `Global auto-accept ${this.interactor.globalAutoAccept ? 'enabled' : 'disabled'} for this session.`
    )

    // If turning global OFF, check if any per-tool flags are still active and inform user
    if (wasOn && !this.interactor.globalAutoAccept) {
      const activePerToolStates = this.checkActivePerToolStates()
      if (activePerToolStates.length > 0) {
        const toolsList = activePerToolStates.join(', ')
        this.interactor.displayText(
          `Note: Individual auto-accept states remain active for: ${toolsList}. ` +
            `Click "Disable auto-accept for all tools" to reset them.`
        )
      }
    }

    this.interactor.sendEvent(
      new GlobalAutoAcceptStateEvent({ globalAutoAcceptEnabled: this.interactor.globalAutoAccept })
    )
    this.interactor.debug(`[TOOLBOX] Global auto-accept toggled to: ${this.interactor.globalAutoAccept}`)
  }

  /**
   * Check which tools have per-tool auto-accept states enabled
   * @returns Array of tool names with active per-tool auto-accept
   */
  private checkActivePerToolStates(): string[] {
    const activeTools: string[] = []

    // Check FileTools
    const fileTools = this.toolFactories.find((f) => f.name === 'FILES')
    if (fileTools && typeof (fileTools as any).getAutoAcceptState === 'function') {
      if ((fileTools as any).getAutoAcceptState()) {
        activeTools.push('file operations')
      }
    }

    // Add checks for other tools with per-tool flags as they're implemented
    // Example:
    // const memoryHandler = this.toolFactories.find((f) => f.name === 'MEMORY')
    // if (memoryHandler && typeof (memoryHandler as any).getAutoAcceptState === 'function') {
    //   if ((memoryHandler as any).getAutoAcceptState()) {
    //     activeTools.push('memory operations')
    //   }
    // }

    return activeTools
  }

  /**
   * Emit current global auto-accept state (for reconnections)
   */
  public emitCurrentGlobalState(): void {
    this.interactor.sendEvent(
      new GlobalAutoAcceptStateEvent({ globalAutoAcceptEnabled: this.interactor.globalAutoAccept || false })
    )
    this.interactor.debug(`[TOOLBOX] Emitted global auto-accept state: ${this.interactor.globalAutoAccept}`)

    // Also emit per-tool reset button visibility state
    this.interactor.sendEvent(
      new PerToolAutoAcceptStateEvent({ hasActivePerToolStates: this.hasActivePerToolStates() })
    )
  }

  /**
   * Check if any per-tool auto-accept is active
   * @returns true if any tool has per-tool auto-accept enabled
   */
  public hasActivePerToolStates(): boolean {
    return this.checkActivePerToolStates().length > 0
  }

  /**
   * Reset all per-tool auto-accept flags
   */
  public resetAllPerToolAutoAccept(): void {
    let resetCount = 0

    // Reset FileTools
    const fileTools = this.toolFactories.find((f) => f.name === 'FILES')
    if (fileTools && typeof (fileTools as any).resetAutoAccept === 'function') {
      ;(fileTools as any).resetAutoAccept()
      resetCount++
    }

    // Add resets for other tools as they're implemented
    // Example:
    // const memoryHandler = this.toolFactories.find((f) => f.name === 'MEMORY')
    // if (memoryHandler && typeof (memoryHandler as any).resetAutoAccept === 'function') {
    //   (memoryHandler as any).resetAutoAccept()
    //   resetCount++
    // }

    this.interactor.displayText(`Reset auto-accept for all tools (${resetCount} tool(s) affected).`)

    // Emit event to notify UI that per-tool states were reset
    this.interactor.sendEvent(new PerToolAutoAcceptResetEvent({}))
    this.interactor.debug(`[TOOLBOX] Reset all per-tool auto-accept flags`)
  }
}
