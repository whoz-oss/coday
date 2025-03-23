import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { UserService } from '../../service/user.service'
import { ProjectService } from '../../service/project.service'
import { McpServerConfig } from '../../model/user-config'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'

/**
 * Factory for MCP server tools that dynamically discovers and exposes tools
 * from configured MCP servers.
 */
// Define interfaces based on MCP SDK types
interface ToolInfo {
  name: string
  description?: string
  inputSchema: {
    type: 'object'
    properties?: Record<string, any>
  }
}

interface ResourceTemplate {
  name: string
  description?: string
  uriTemplate: string
  contentType?: string
}

export class McpToolsFactory extends AssistantToolFactory {
  name = 'MCP'
  private toolsCache: Map<string, CodayTool[]> = new Map()
  private clientInstances: Map<string, Client> = new Map()

  constructor(
    interactor: Interactor,
    private userService: UserService,
    // This is unused but kept for future extensibility
    // @ts-ignore
    private projectService: ProjectService
  ) {
    super(interactor)
  }

  /**
   * Check if any MCP server configuration has changed
   *
   * @param context Current command context
   * @returns True if any server config has changed, false otherwise
   */
  protected hasChanged(context: CommandContext): boolean {
    // Check if any server config has changed
    const configs = this.getServerConfigs(context)
    if (!configs || configs.length === 0) return false

    // Check if any server configuration is not in the cache
    for (const config of configs) {
      const cacheKey = this.getCacheKey(config.id)
      if (!this.toolsCache.has(cacheKey)) {
        return true
      }
    }

    return false
  }

  /**
   * Get a unique cache key for storing tools from this server
   */
  private getCacheKey(serverId: string): string {
    return `mcp-server-${serverId}`
  }

  /**
   * Get configuration for all enabled MCP servers
   *
   * @param context Current command context
   * @returns Array of enabled MCP server configurations
   */
  private getServerConfigs(context: CommandContext): McpServerConfig[] {
    try {
      // Get MCP servers from user config
      const userMcpServers = this.userService.config.mcp?.servers || []

      // Get MCP servers from project data
      const projectMcpServers: McpServerConfig[] = []
      if (context.data.mcp?.servers) {
        projectMcpServers.push(...context.data.mcp.servers)
      }

      // Combine configurations, with project configs taking precedence
      const allServers = [...userMcpServers]

      // Add or override with project servers
      for (const projectServer of projectMcpServers) {
        const existingIndex = allServers.findIndex((s) => s.id === projectServer.id)
        if (existingIndex >= 0) {
          allServers[existingIndex] = projectServer
        } else {
          allServers.push(projectServer)
        }
      }

      // Filter to only enabled servers
      return allServers.filter((server) => server.enabled)
    } catch (error) {
      this.interactor.error(`Error retrieving MCP server configurations: ${error}`)
      return []
    }
  }

  /**
   * Creates or retrieves an MCP client instance
   */
  private async getClientInstance(serverConfig: McpServerConfig): Promise<Client> {
    const serverId = serverConfig.id

    // Check if we already have an instance for this server
    if (this.clientInstances.has(serverId)) {
      return this.clientInstances.get(serverId)!
    }

    // Create a new client instance
    const client = new Client(
      {
        name: 'Coday MCP Client',
        version: '1.0.0',
      },
      {
        capabilities: {
          // Add supported capabilities
          // Note: These may need to be adjusted based on actual needs
          toolInvocation: {},
          resources: {},
        },
      }
    )

    // Create the appropriate transport based on the server configuration
    let transport: Transport

    // For now, only support command-based stdio transport
    if (serverConfig.url) {
      throw new Error(`Remote HTTP/HTTPS MCP servers are not supported yet. Use local command-based servers instead.`)
    } else if (serverConfig.command) {
      // Stdio transport - launch the command as a child process
      transport = new StdioClientTransport({
        command: serverConfig.command,
        args: serverConfig.args || [],
      })
      this.interactor.displayText(`Starting MCP server ${serverConfig.name} with command: ${serverConfig.command}`)
    } else {
      throw new Error(`MCP server ${serverConfig.name} has no command configured. Only local command-based servers are supported.`)
    }

    // Connect to the server
    await client.connect(transport)

    // Store the instance for future use
    this.clientInstances.set(serverId, client)

    return client
  }

  /**
   * Connect to the MCP server and retrieve available tools
   *
   * This implementation connects to the MCP server, retrieves its schema/capabilities,
   * and creates proxy tools for all resources and functions available on the server.
   *
   * @param serverConfig The MCP server configuration
   * @param context Current command context
   * @param agentName Name of the agent requesting tools
   */
  private async connectAndRetrieveTools(
    serverConfig: McpServerConfig,
    context: CommandContext,
    agentName: string
  ): Promise<CodayTool[]> {
    try {
      const client = await this.getClientInstance(serverConfig)
      this.interactor.displayText(`Connected to MCP server: ${serverConfig.name}`)

      const tools: CodayTool[] = []

      // Get all resource templates from the server
      try {
        const result = await client.listResourceTemplates()
        if (result && result.templates && Array.isArray(result.templates)) {
          for (const template of result.templates) {
            tools.push(this.createResourceTool(serverConfig, client, template))
          }
        }
      } catch (err) {
        this.interactor.warn(`Error listing resource templates from MCP server ${serverConfig.name}: ${err}`)
      }

      // Get all tools from the server
      try {
        const result = await client.listTools()
        if (result && result.tools && Array.isArray(result.tools)) {
          for (const tool of result.tools) {
            tools.push(this.createFunctionTool(serverConfig, client, tool))
          }
        }
      } catch (err) {
        this.interactor.warn(`Error listing tools from MCP server ${serverConfig.name}: ${err}`)
      }

      return tools
    } catch (error) {
      this.interactor.error(`Error connecting to MCP server ${serverConfig.name}: ${error}`)
      return []
    }
  }

  /**
   * Create a Coday tool for an MCP resource
   *
   * @param serverConfig The MCP server configuration
   * @param client The MCP client instance
   * @param resource The MCP resource definition
   */
  private createResourceTool(serverConfig: McpServerConfig, client: Client, resource: ResourceTemplate): CodayTool {
    const resourceName = `mcp__${serverConfig.id}__${resource.name}`

    const getResource = async (args: Record<string, any>) => {
      try {
        // Build the resource URI with parameters
        const uri = resource.uriTemplate.replace(/\{([^}]+)\}/g, (match: string, param: string) => {
          return encodeURIComponent(args[param] || '')
        })

        // Fetch the resource
        const result = await client.readResource({ uri })

        if (!result || !result.contents) {
          throw new Error(`No content returned from resource ${resource.name}`)
        }

        // Return the content as a string or JSON
        return result.contents.map((content: any) => {
          // For text resources
          if ('text' in content) {
            return {
              uri: content.uri,
              text: content.text,
              mimeType: content.mimeType || 'text/plain',
            }
          }
          // For blob resources (will be base64 encoded)
          else if ('blob' in content) {
            return {
              uri: content.uri,
              blob: content.blob,
              mimeType: content.mimeType || 'application/octet-stream',
            }
          }
          return content
        })
      } catch (error) {
        this.interactor.error(`Error retrieving resource ${resource.name}: ${error}`)
        throw new Error(`Failed to retrieve resource: ${error}`)
      }
    }

    // Extract parameters from URI template
    const params: Record<string, any> = {}
    const paramMatches = resource.uriTemplate.match(/\{([^}]+)\}/g) || []
    for (const match of paramMatches) {
      const paramName = match.slice(1, -1) // Remove { and }
      params[paramName] = { type: 'string', description: `Parameter ${paramName} for resource ${resource.name}` }
    }

    return {
      type: 'function',
      function: {
        name: resourceName,
        description: resource.description || `Resource from MCP server ${serverConfig.name}`,
        parameters: {
          type: 'object',
          properties: params,
        },
        parse: JSON.parse,
        function: getResource,
      },
    }
  }

  /**
   * Create a Coday tool for an MCP function tool
   *
   * @param serverConfig The MCP server configuration
   * @param client The MCP client instance
   * @param tool The MCP tool definition
   */
  private createFunctionTool(serverConfig: McpServerConfig, client: Client, tool: ToolInfo): CodayTool {
    const toolName = `mcp__${serverConfig.id}__${tool.name}`

    const callFunction = async (args: Record<string, any>) => {
      try {
        // Call the tool function
        const result = await client.callTool({
          name: tool.name,
          arguments: args,
        })

        // MCP can return either a content array or a toolResult
        if (result && 'content' in result && Array.isArray(result.content)) {
          // Process content array
          return result.content.map((item: any) => {
            if (item.type === 'text') {
              return item.text
            } else if (item.type === 'resource') {
              return item.resource
            }
            return item
          })
        } else if (result && 'toolResult' in result) {
          // Return direct tool result
          return result.toolResult
        }

        return result
      } catch (error) {
        this.interactor.error(`Error calling function ${tool.name}: ${error}`)
        throw new Error(`Failed to call function: ${error}`)
      }
    }

    return {
      type: 'function',
      function: {
        name: toolName,
        description: tool.description || `Function from MCP server ${serverConfig.name}`,
        parameters: {
          type: 'object',
          properties: tool.inputSchema?.properties || {}
        },
        parse: JSON.parse,
        function: callFunction,
      },
    }
  }

  /**
   * Build tools for all configured MCP servers
   *
   * This method connects to each MCP server and builds proxies for all available
   * tools on those servers. It handles connection errors and authentication.
   *
   * @param context Current command context
   * @param agentName Name of the agent requesting tools
   */
  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const serverConfigs = this.getServerConfigs(context)
    if (!serverConfigs || serverConfigs.length === 0) {
      return []
    }

    const allTools: CodayTool[] = []

    // Process each server configuration
    for (const serverConfig of serverConfigs) {
      const cacheKey = this.getCacheKey(serverConfig.id)

      // Check if we have cached tools for this server
      if (this.toolsCache.has(cacheKey)) {
        const cachedTools = this.toolsCache.get(cacheKey) || []
        allTools.push(...cachedTools)
        continue
      }

      try {
        // Connect to the server and retrieve tools
        const tools = await this.connectAndRetrieveTools(serverConfig, context, agentName)

        // Cache the tools for future use
        this.toolsCache.set(cacheKey, tools)

        // Add tools to the result
        allTools.push(...tools)
      } catch (error) {
        this.interactor.error(`Error retrieving tools from MCP server ${serverConfig.name}: ${error}`)
      }
    }

    return allTools
  }
}
