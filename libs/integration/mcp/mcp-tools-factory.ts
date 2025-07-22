import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { CommandContext, Interactor, McpServerConfig } from '../../model'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { ResourceTemplate, ToolInfo } from './types'
import { ChildProcess, spawn } from 'child_process'

export class McpToolsFactory extends AssistantToolFactory {
  /**
   * Client promise, ensuring all calls to buildTools use the same instance of client
   * @private
   */
  private clientPromise: Promise<Client> | undefined

  /**
   * Tools promise, ensuring all calls to buildTools
   * @private
   */
  private toolsPromise: Promise<CodayTool[]> | undefined

  /**
   * Handle to the inspector process if debug mode is enabled
   * @private
   */
  private inspectorProcess: ChildProcess | undefined

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
    console.log(`Closing mcp client ${this.serverConfig.name}`)
    
    try {
      const client = await this.clientPromise
      await client?.close()
    } catch (error) {
      // If the client failed to initialize, that's fine - nothing to close
      console.log(`MCP client ${this.serverConfig.name} was already failed/closed`)
    }
    
    if (this.inspectorProcess) {
      console.log(`Stopping MCP Inspector process for ${this.serverConfig.name}`)
      this.inspectorProcess.kill()
      this.inspectorProcess = undefined
    }
    console.log(`Closed mcp client ${this.serverConfig.name}`)
  }

  protected async buildTools(_context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    // if tools are already created, return them
    if (this.tools.length) return this.tools

    // if server is not enabled, no tools to return
    if (!this.serverConfig.enabled) return []

    // check if the client has already started being instanciated
    if (!this.clientPromise) {
      this.clientPromise = this.buildClient()
    }

    const client: Client = await this.clientPromise

    if (!this.toolsPromise) {
      this.toolsPromise = this.buildInternalTools(client)
    }

    return this.toolsPromise
  }

  private async buildClient(): Promise<Client> {
    // now time to create the client
    const instance = new Client(
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
      const transportOptions: any = {}

      // Inspector process: only for debug mode, not used for tool integration
      if (this.serverConfig.debug) {
        // Start inspector process just for UI/debugging, not for tool integration
        if (!this.inspectorProcess) {
          const inspectorCommand = 'npx'
          const inspectorArgs = [
            '@modelcontextprotocol/inspector',
            this.serverConfig.command,
            ...(this.serverConfig.args || []),
          ]
          const inspectorOptions: any = {}
          if (this.serverConfig.env && Object.keys(this.serverConfig.env).length > 0) {
            inspectorOptions.env = { ...process.env, ...this.serverConfig.env }
          }
          if (this.serverConfig.cwd) {
            inspectorOptions.cwd = this.serverConfig.cwd
          }
          console.log(`Starting MCP Inspector process for ${this.serverConfig.name}}`)
          this.inspectorProcess = spawn(inspectorCommand, inspectorArgs, {
            stdio: 'inherit',
            ...inspectorOptions,
          })
          this.inspectorProcess.on('exit', (code, signal) => {
            console.log(`MCP Inspector process for ${this.serverConfig.name} exited (code: ${code}, signal: ${signal})`)
          })
        }
      }
      // Main MCP transport for tools (never wrapped in inspector)
      transportOptions.command = this.serverConfig.command
      transportOptions.args = this.serverConfig.args || []
      if (this.serverConfig.env && Object.keys(this.serverConfig.env).length > 0) {
        transportOptions.env = this.serverConfig.env
        console.log(`Using custom environment variables for MCP server ${this.serverConfig.name}`)
      }
      if (this.serverConfig.cwd) {
        transportOptions.cwd = this.serverConfig.cwd
        console.log(`Using working directory: ${this.serverConfig.cwd}`)
      }
      transport = new StdioClientTransport(transportOptions)
      console.log(`Starting MCP server ${this.serverConfig.name} with command: ${transportOptions.command}`)
    } else {
      throw new Error(
        `MCP server ${this.serverConfig.name} has no command configured. Only local command-based servers are supported.`
      )
    }
    // Connect to the server
    await instance.connect(transport)
    return instance
  }

  private async buildInternalTools(client: Client): Promise<CodayTool[]> {
    const results: CodayTool[] = []

    // Get all resource templates from the server
    try {
      const result = await client.listResourceTemplates()
      if (result && result.templates && Array.isArray(result.templates)) {
        for (const template of result.templates) {
          results.push(this.createResourceTool(this.serverConfig, client, template))
        }
      }
    } catch (err) {
      // Method not found errors (-32601) are expected for MCP servers that don't implement resource templates
      // Only warn if it's not a method not found error
      if (err instanceof Error && !err.message.includes('-32601: Method not found')) {
        this.interactor.warn(`Error listing resource templates from MCP server ${this.serverConfig.name}: ${err}`)
      } else {
        this.interactor.debug(
          `MCP server ${this.serverConfig.name} doesn't support resource templates, continuing with tools only.`
        )
      }
    }

    // Get all tools from the server
    try {
      const result = await client.listTools()
      if (result && result.tools && Array.isArray(result.tools)) {
        for (const tool of result.tools) {
          results.push(this.createFunctionTool(this.serverConfig, client, tool as ToolInfo))
        }
      }
    } catch (err) {
      this.interactor.warn(`Error listing tools from MCP server ${this.serverConfig.name}: ${err}`)
    }

    if (this.serverConfig.debug) {
      const toolNames = results.map((t) => `- ${t.function.name}\n`).join()
      this.interactor.debug(`MCP ${this.serverConfig.name}:\n${toolNames}`)
    }

    return results
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
        const uri = resource.uriTemplate.replace(/\{([^}]+)\}/g, (_match: string, param: string) => {
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
    const toolName = `${serverConfig.name}__${tool.name}`

    const callFunction = async (args: Record<string, any>) => {
      // Log the function call with smart formatting

      if (this.serverConfig.debug) {
        this.interactor.debug(`${toolName} input:\n\n` + '```json\n' + JSON.stringify(args) + '\n```')
      }
      try {
        // Call the tool function
        const result = await client.callTool({
          name: tool.name,
          arguments: args,
        })
        if (this.serverConfig.debug) {
          this.interactor.debug('MCP tool output:\n\n```json\n' + JSON.stringify(result) + '\n```')
        }

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
