import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { CommandContext, McpServerConfig } from '../../model'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { ResourceTemplate, ToolInfo } from './types'
import { ChildProcess, spawn } from 'child_process'

const MCP_CONNECT_TIMEOUT = 15000 // in ms

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

  /**
   * Reference to the transport for explicit cleanup
   * @private
   */
  private transport: Transport | undefined

  /**
   * PID of the spawned MCP server process
   * Used for manual cleanup if transport.close() fails
   * @private
   */
  private serverProcessPid: number | null = null

  name: string = 'Not defined yet'

  private errorLogged: boolean = false

  /**
   * Timestamp of the last tool or resource call.
   * Updated automatically on each tool/resource execution.
   * Used for monitoring and debugging (not for TTL-based cleanup).
   */
  lastUsed: number = Date.now()

  constructor(private serverConfig: McpServerConfig) {
    super()
    this.name = serverConfig.name
  }

  async kill(): Promise<void> {
    this.tools = []
    console.log(`Closing mcp client ${this.serverConfig.name}`)

    // Kill inspector process first if it exists
    if (this.inspectorProcess) {
      console.log(`Stopping MCP Inspector process for ${this.serverConfig.name}`)
      try {
        this.inspectorProcess.kill('SIGTERM')
        // Give it a moment to terminate gracefully
        await new Promise((resolve) => setTimeout(resolve, 100))
        // Force kill if still alive
        if (!this.inspectorProcess.killed) {
          this.inspectorProcess.kill('SIGKILL')
        }
      } catch (error) {
        console.log(`Error killing inspector process: ${error}`)
      }
      this.inspectorProcess = undefined
    }

    // Close the MCP client
    try {
      const client = await this.clientPromise
      await client?.close()
    } catch (error) {
      // If the client failed to initialize, that's fine - nothing to close
      console.log(`MCP client ${this.serverConfig.name} was already failed/closed`)
    }

    // Explicitly close the transport to ensure child process cleanup
    if (this.transport) {
      try {
        await this.transport.close()
        console.log(`Transport closed for ${this.serverConfig.name}`)
      } catch (error) {
        console.log(`Error closing transport for ${this.serverConfig.name}: ${error}`)
      }
      this.transport = undefined
    }

    console.log(`Closed mcp client ${this.serverConfig.name}`)
  }

  protected async buildTools(_context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    // if tools are already created, return them
    if (this.tools.length) {
      return this.tools
    }

    // if server is not enabled, no tools to return
    if (!this.serverConfig.enabled) {
      console.debug(`[MCP] Server ${this.serverConfig.name} is disabled, skipping`)
      return []
    }

    try {
      // check if the client has already started being instanciated
      if (!this.clientPromise) {
        console.log(`Initializing MCP server ${this.serverConfig.name}...`)
        this.clientPromise = this.buildClient()
      }

      const client: Client = await this.clientPromise

      if (!this.toolsPromise) {
        this.toolsPromise = this.buildInternalTools(client)
      }

      const tools = await this.toolsPromise
      console.log(`MCP server ${this.serverConfig.name} loaded ${tools.length} tools successfully`)
      return tools
    } catch (error) {
      // Log the error but don't crash the entire agent initialization
      const errorMessage = error instanceof Error ? error.message : String(error)

      // Only log the error once per factory instance
      if (!this.errorLogged) {
        this.errorLogged = true
        console.error(`[MCP] Server ${this.serverConfig.name} failed to initialize: ${errorMessage}`)
        console.warn(`[MCP] Server '${this.serverConfig.name}' is unavailable and will be skipped: ${errorMessage}`)
      }

      // Return empty tools array to allow other tools to work
      return []
    }
  }

  private async buildClient(): Promise<Client> {
    // now time to create the client
    const instance = new Client(
      {
        name: 'Coday MCP Client',
        version: '1.0.0',
      },
      {
        capabilities: {},
      }
    )

    // Create the appropriate transport based on the server configuration

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
            // Use isolated environment (same as main transport for consistency)
            // serverConfig.env already includes whitelisted variables from merger
            inspectorOptions.env = this.serverConfig.env
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
      this.transport = new StdioClientTransport(transportOptions)
      console.log(`Starting MCP server ${this.serverConfig.name} with command: ${transportOptions.command}`)

      // Add error handling for transport to catch early failures
      this.transport.onerror = (error) => {
        console.error(`MCP server ${this.serverConfig.name} transport error:`, error)
      }

      // Store the process PID after connection for manual cleanup if needed
      // Note: PID will be available after start() is called in connect()
      this.transport.onclose = () => {
        console.log(`MCP server ${this.serverConfig.name} transport closed`)
        this.serverProcessPid = null
      }
    } else {
      throw new Error(
        `MCP server ${this.serverConfig.name} has no command configured. Only local command-based servers are supported.`
      )
    }
    // Connect to the server with timeout and better error handling
    try {
      // Set a shorter timeout for MCP connections (default is 60s, we use MCP_CONNECT_TIMEOUT)
      const connectPromise = instance.connect(this.transport)
      const timeoutPromise = new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error(`Connection timeout after ${MCP_CONNECT_TIMEOUT}ms`)), MCP_CONNECT_TIMEOUT)
      )

      await Promise.race([connectPromise, timeoutPromise])

      // Store the process PID for manual cleanup if needed
      if (this.transport && 'pid' in this.transport) {
        this.serverProcessPid = (this.transport as any).pid
        console.log(`Successfully connected to MCP server ${this.serverConfig.name} (PID: ${this.serverProcessPid})`)
      } else {
        console.log(`Successfully connected to MCP server ${this.serverConfig.name}`)
      }

      return instance
    } catch (error) {
      // Cleanup on connection failure
      await this.cleanupOnError()

      // Enhanced error reporting
      const errorMessage = error instanceof Error ? error.message : String(error)
      console.error(`Failed to connect to MCP server ${this.serverConfig.name}: ${errorMessage}`)

      // Check if it's a Docker-related issue
      if (
        errorMessage.includes('docker') ||
        errorMessage.includes('Docker') ||
        errorMessage.includes('container') ||
        errorMessage.includes('Container')
      ) {
        throw new Error(
          `MCP server ${this.serverConfig.name} failed to start. This may be because Docker is not available or the Docker container failed to start. Original error: ${errorMessage}`
        )
      }

      // Check if it's a timeout
      if (errorMessage.includes('timeout') || errorMessage.includes('Connection timeout')) {
        throw new Error(
          `MCP server ${this.serverConfig.name} did not respond within ${MCP_CONNECT_TIMEOUT}ms. Check if the server command is correct and the server starts quickly. Original error: ${errorMessage}`
        )
      }

      // Check for common command not found errors
      if (errorMessage.includes('ENOENT') || errorMessage.includes('command not found')) {
        throw new Error(
          `MCP server ${this.serverConfig.name} command not found. Check that the command '${this.serverConfig.command}' is available in your PATH. Original error: ${errorMessage}`
        )
      }

      throw new Error(`MCP server ${this.serverConfig.name} connection failed: ${errorMessage}`)
    }
  }

  /**
   * Cleanup resources when connection fails
   * @private
   */
  private async cleanupOnError(): Promise<void> {
    console.log(`Cleaning up failed MCP connection for ${this.serverConfig.name}`)

    // Kill inspector process if it was started
    if (this.inspectorProcess) {
      try {
        this.inspectorProcess.kill('SIGTERM')
        await new Promise((resolve) => setTimeout(resolve, 100))
        if (!this.inspectorProcess.killed) {
          this.inspectorProcess.kill('SIGKILL')
        }
      } catch (error) {
        console.log(`Error killing inspector during cleanup: ${error}`)
      }
      this.inspectorProcess = undefined
    }

    // Close transport to cleanup child process
    if (this.transport) {
      try {
        await this.transport.close()
      } catch (error) {
        console.log(`Error closing transport during cleanup: ${error}`)
      }
      this.transport = undefined
    }

    // Fallback: manually kill the process if it's still running
    if (this.serverProcessPid) {
      try {
        // Check if process still exists
        process.kill(this.serverProcessPid, 0)
        // Process exists, kill it
        console.log(`Force killing MCP server process ${this.serverProcessPid} for ${this.serverConfig.name}`)
        process.kill(this.serverProcessPid, 'SIGTERM')
        // Give it a moment to terminate gracefully
        await new Promise((resolve) => setTimeout(resolve, 100))
        // Check if still alive and force kill
        try {
          process.kill(this.serverProcessPid, 0)
          process.kill(this.serverProcessPid, 'SIGKILL')
          console.log(`Force killed with SIGKILL: ${this.serverProcessPid}`)
        } catch {
          // Process already dead, good
        }
      } catch (error: any) {
        // ESRCH means process doesn't exist, that's fine
        if (error.code !== 'ESRCH') {
          console.log(`Error killing MCP server process ${this.serverProcessPid}: ${error}`)
        }
      }
      this.serverProcessPid = null
    }
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
        console.warn(`[MCP] Error listing resource templates from server ${this.serverConfig.name}: ${err}`)
      } else {
        console.debug(
          `[MCP] Server ${this.serverConfig.name} doesn't support resource templates, continuing with tools only.`
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
      console.warn(`[MCP] Error listing tools from server ${this.serverConfig.name}: ${err}`)
    }

    if (this.serverConfig.debug) {
      const toolNames = results.map((t) => `- ${t.function.name}\n`).join()
      console.debug(`[MCP] ${this.serverConfig.name}:\n${toolNames}`)
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
        // Update last used timestamp
        this.lastUsed = Date.now()

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
        console.error(`[MCP] Error retrieving resource ${resource.name}: ${error}`)
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
      // Update last used timestamp
      this.lastUsed = Date.now()

      // Log the function call with smart formatting
      if (this.serverConfig.debug) {
        console.debug(`[MCP] ${toolName} input:\n\n` + '```json\n' + JSON.stringify(args) + '\n```')
      }
      try {
        // Call the tool function
        const result = await client.callTool({
          name: tool.name,
          arguments: args,
        })
        if (this.serverConfig.debug) {
          console.debug('[MCP] Tool output:\n\n```json\n' + JSON.stringify(result) + '\n```')
        }

        // MCP can return either a content array or a toolResult
        if (result && 'content' in result && Array.isArray(result.content)) {
          // Process content array
          const processedContent = result.content.map((item: any) => {
            // Text content
            if (item.type === 'text') {
              return item.text
            }
            // Image content - convert to Coday MessageContent format
            // Note: Images will be stringified by tool-set for now
            // Future: implement proper image handling via alternative mechanism
            if (item.type === 'image' && item.data) {
              return {
                type: 'image',
                content: item.data, // base64 string
                mimeType: item.mimeType || 'image/png',
                // Add dimensions if available
                ...(item.width && { width: item.width }),
                ...(item.height && { height: item.height }),
              }
            }
            // Resource content
            if (item.type === 'resource') {
              return item.resource
            }
            // Unknown - return as is (will be stringified by tool-set)
            return item
          })

          // Single item optimization
          if (processedContent.length === 1) {
            return processedContent[0]
          }

          // Multiple items - return array (will be stringified by tool-set)
          return processedContent
        } else if (result && 'toolResult' in result) {
          // Return direct tool result
          return result.toolResult
        }

        return result
      } catch (error) {
        console.error(`[MCP] Error calling function ${tool.name}: ${error}`)
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
