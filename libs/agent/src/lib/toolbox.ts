import {
  Agent,
  AgentSummary,
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  GetToolsInput,
  IntegrationConfig,
  Interactor,
  Killable,
  McpServerConfig,
  OAuthCallbackEvent,
} from '@coday/model'
import { AiTools, DelegateTools } from '@coday/integrations-ai'
import { McpToolsFactory } from '@coday/mcp'
import { CoreTools, MemoryTools, ProjectScriptsTools, TmuxTools } from '@coday/integration'
import { FileTools } from '@coday/integrations-file'
import { GitTools } from '@coday/integrations-git'
import { GitLabTools } from '@coday/integrations-gitlab'
import { ConfluenceTools } from '@coday/integrations-confluence'
import { ZendeskTools } from '@coday/integrations-zendesk-articles'
import { JiraTools } from '@coday/integrations-jira'
import { SlackTools } from '@coday/integrations-slack'
import { BasecampTools } from '@coday/integrations-basecamp'
import { CodayServices } from '@coday/coday-services'

export class Toolbox implements Killable {
  // Registry: integration type -> factory constructor function
  private readonly factoryConstructors: Map<string, (name: string, config: IntegrationConfig) => AssistantToolFactory>

  // Factory instances cache (created on-demand per instance name)
  private readonly factoryInstances: Map<string, AssistantToolFactory> = new Map()

  private readonly mcpConfigs: McpServerConfig[]
  private tools: CodayTool[] = []

  constructor(
    private readonly interactor: Interactor,
    private readonly services: CodayServices,
    agentFind: (nameStart: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
    agentSummaries: () => AgentSummary[]
  ) {
    // Store MCP configs for lazy initialization via pool
    this.mcpConfigs = services.mcp.getMergedConfiguration().servers

    // Initialize factory constructors registry for ALL tool types
    this.factoryConstructors = new Map<string, (name: string, config: IntegrationConfig) => AssistantToolFactory>()

    // Core tools (always available, no config needed)
    this.factoryConstructors.set(
      CoreTools.TYPE,
      (name) => new CoreTools(interactor, name, {}, this.services.options?.baseUrl)
    )
    this.factoryConstructors.set(AiTools.TYPE, (name) => new AiTools(interactor, agentSummaries, name, {}))
    this.factoryConstructors.set(
      DelegateTools.TYPE,
      (name) => new DelegateTools(interactor, agentFind, agentSummaries, name, {})
    )
    this.factoryConstructors.set(FileTools.TYPE, (name, config) => new FileTools(interactor, name, config))
    this.factoryConstructors.set(ProjectScriptsTools.TYPE, (name) => new ProjectScriptsTools(interactor, name, {}))
    this.factoryConstructors.set(MemoryTools.TYPE, (name) => new MemoryTools(interactor, services.memory, name, {}))

    // Integration tools (require config)
    this.factoryConstructors.set(
      GitTools.TYPE,
      (name, config) => new GitTools(interactor, services.integration, name, config)
    )
    this.factoryConstructors.set(
      GitLabTools.TYPE,
      (name, config) => new GitLabTools(interactor, services.integration, name, config)
    )
    this.factoryConstructors.set(
      ConfluenceTools.TYPE,
      (name, config) => new ConfluenceTools(interactor, services.integration, name, config)
    )
    this.factoryConstructors.set(
      ZendeskTools.TYPE,
      (name, config) => new ZendeskTools(interactor, services.integration, name, config)
    )
    this.factoryConstructors.set(
      JiraTools.TYPE,
      (name, config) => new JiraTools(interactor, services.integration, name, config)
    )
    this.factoryConstructors.set(
      SlackTools.TYPE,
      (name, config) => new SlackTools(interactor, services.integration, name, config)
    )
    this.factoryConstructors.set(
      BasecampTools.TYPE,
      (name, config) => new BasecampTools(interactor, services.integration, services.user, name, config)
    )
    this.factoryConstructors.set(
      TmuxTools.TYPE,
      (name) => new TmuxTools(interactor, name)
    )
  }

  async kill(): Promise<void> {
    console.log(`[TOOLBOX] Closing tool factories`)
    await Promise.all(Array.from(this.factoryInstances.values()).map((f) => f.kill()))
    console.log(`[TOOLBOX] Closed tool factories`)

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

    // Collect all requested factories
    const allFactories: AssistantToolFactory[] = []

    // Process requested integrations (on-demand instantiation)
    if (integrations) {
      const mergedIntegrations = this.services.integration.integrations

      for (const [instanceName] of integrations) {
        // Check if it's already instantiated
        if (this.factoryInstances.has(instanceName)) {
          const factory = this.factoryInstances.get(instanceName)!
          allFactories.push(factory)
          continue
        }

        // Otherwise, it's an integration that needs config
        const config = mergedIntegrations[instanceName]

        if (!config) {
          this.interactor.debug(`Integration config '${instanceName}' not found`)
          continue
        }

        // Determine type (fallback to instance name for backward compatibility)
        const type = config.type || instanceName

        // Find factory constructor for this type
        const constructor = this.factoryConstructors.get(type)

        if (!constructor) {
          this.interactor.debug(`No factory constructor for integration type '${type}'`)
          continue
        }

        // Get or create factory instance (keyed by instanceName, not type)
        let factory = this.factoryInstances.get(instanceName)
        if (!factory) {
          try {
            factory = constructor(instanceName, config)
            this.factoryInstances.set(instanceName, factory)
            this.interactor.debug(`Created integration factory '${instanceName}' of type '${type}'`)
          } catch (error) {
            this.interactor.debug(`Error creating factory for '${instanceName}': ${error}`)
            continue
          }
        }

        allFactories.push(factory)
      }
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

    // Combine all factories
    allFactories.push(...mcpFactories)

    try {
      // Process each factory to get their tools
      const toolResults = await Promise.all(
        allFactories.map(async (factory) => {
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
    const factory = this.factoryInstances.get(event.integrationName)

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
