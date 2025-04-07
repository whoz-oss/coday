import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { CommandContext, Interactor, McpServerConfig } from '../../model'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { ResourceTemplate, ToolInfo } from './types'

export class McpToolsFactory extends AssistantToolFactory {
  private client: Client | undefined
  name: string = 'Not defined yet'

  constructor(
    interactor: Interactor,
    private serverConfig: McpServerConfig
  ) {
    super(interactor)
    this.name = serverConfig.name
  }

  async kill(): Promise<void> {
    this.tools = []
    await this.client?.close()
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    if (this.tools.length) return this.tools
    if (!this.serverConfig.enabled) return []

    // now time to create the client
    this.client = new Client(
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
    if (this.serverConfig.url) {
      throw new Error(`Remote HTTP/HTTPS MCP servers are not supported yet. Use local command-based servers instead.`)
    } else if (this.serverConfig.command) {
      // Stdio transport - launch the command as a child process
      const transportOptions: any = {
        command: this.serverConfig.command,
        args: this.serverConfig.args || [],
      }

      // Add environment variables if specified
      if (this.serverConfig.env && Object.keys(this.serverConfig.env).length > 0) {
        transportOptions.env = this.serverConfig.env
        this.interactor.displayText(`Using custom environment variables for MCP server ${this.serverConfig.name}`)
      }

      // Add working directory if specified
      if (this.serverConfig.cwd) {
        transportOptions.cwd = this.serverConfig.cwd
        this.interactor.displayText(`Using working directory: ${this.serverConfig.cwd}`)
      }

      transport = new StdioClientTransport(transportOptions)
      this.interactor.displayText(
        `Starting MCP server ${this.serverConfig.name} with command: ${this.serverConfig.command}`
      )
    } else {
      throw new Error(
        `MCP server ${this.serverConfig.name} has no command configured. Only local command-based servers are supported.`
      )
    }

    // Connect to the server
    await this.client.connect(transport)

    // Get all resource templates from the server
    try {
      const result = await this.client.listResourceTemplates()
      if (result && result.templates && Array.isArray(result.templates)) {
        for (const template of result.templates) {
          this.tools.push(this.createResourceTool(this.serverConfig, this.client, template))
        }
      }
    } catch (err) {
      this.interactor.warn(`Error listing resource templates from MCP server ${this.serverConfig.name}: ${err}`)
    }

    // Get all tools from the server
    try {
      const result = await this.client.listTools()
      if (result && result.tools && Array.isArray(result.tools)) {
        for (const tool of result.tools) {
          this.tools.push(this.createFunctionTool(this.serverConfig, this.client, tool as ToolInfo))
        }
      }
    } catch (err) {
      this.interactor.warn(`Error listing tools from MCP server ${this.serverConfig.name}: ${err}`)
    }
    return this.tools
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
          properties: tool.inputSchema?.properties || {},
        },
        parse: JSON.parse,
        function: callFunction,
      },
    }
  }
}
