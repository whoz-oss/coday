import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js'
import { ResourceTemplate, ToolInfo } from './types'
import { ChildProcess, spawn } from 'child_process'
import { Interactor, OAuthCallbackEvent, ServerInteractor } from '@coday/model'
import { AssistantToolFactory } from '@coday/model'
import { CodayTool } from '@coday/model'
import { McpServerConfig } from '@coday/model'
import { CommandContext } from '@coday/model'
import { UserService } from '@coday/service'
import { McpOAuthProvider } from './mcp-oauth-provider'

const MCP_CONNECT_TIMEOUT = 15000 // in ms
const MCP_OAUTH_CONNECT_TIMEOUT = 5 * 60 * 1000 // 5 minutes for OAuth flows requiring user interaction

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

  /** OAuth provider instance for remote MCP servers with oauth2: true */
  private oauthProvider: McpOAuthProvider | undefined

  /**
   * Whether kill() has been explicitly called.
   * Prevents reconnection attempts after intentional shutdown.
   */
  private _killed: boolean = false

  /**
   * Whether the current transport is legacy SSE (fallback from StreamableHTTP).
   * Used for diagnostics.
   */
  private _usingLegacySSE: boolean = false

  /** Current reconnection attempt count (reset on successful connect) */
  private _reconnectAttempts: number = 0

  /** Delay in ms for the next reconnection attempt (doubles each time, capped at 30 s) */
  private _reconnectDelay: number = 2000

  private readonly mcpInteractor?: Interactor
  private readonly mcpUserService?: UserService
  private readonly mcpProjectName?: string
  private readonly mcpBaseUrl?: string

  constructor(
    private readonly serverConfig: McpServerConfig,
    mcpInteractor?: Interactor,
    mcpUserService?: UserService,
    mcpProjectName?: string,
    mcpBaseUrl?: string
  ) {
    super(new ServerInteractor('not used'), serverConfig.name)
    this.name = serverConfig.name
    this.mcpInteractor = mcpInteractor
    this.mcpUserService = mcpUserService
    this.mcpProjectName = mcpProjectName
    this.mcpBaseUrl = mcpBaseUrl
  }

  /**
   * Route an OAuth callback to the embedded McpOAuthProvider.
   * Called by Toolbox when an OAuthCallbackEvent arrives for this MCP server id.
   */
  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    await this.oauthProvider?.handleCallback(event)
  }

  async kill(): Promise<void> {
    this._killed = true
    this._reconnectAttempts = 0
    this._reconnectDelay = 2000
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
        // Nullify PID synchronously after successful close
        // (the async onclose handler also does this, but we need it before the fallback check below)
        this.serverProcessPid = null
      } catch (error) {
        console.log(`Error closing transport for ${this.serverConfig.name}: ${error}`)
      }
      this.transport = undefined
    }

    // Fallback: manually kill the process if transport.close() failed silently
    if (this.serverProcessPid) {
      try {
        process.kill(this.serverProcessPid, 0)
        console.log(`Force killing MCP server process ${this.serverProcessPid} for ${this.serverConfig.name}`)
        process.kill(this.serverProcessPid, 'SIGTERM')
        await new Promise((resolve) => setTimeout(resolve, 100))
        try {
          process.kill(this.serverProcessPid, 0)
          process.kill(this.serverProcessPid, 'SIGKILL')
          console.log(`Force killed with SIGKILL: ${this.serverProcessPid}`)
        } catch {
          // Process already dead, good
        }
      } catch (error: any) {
        if (error.code !== 'ESRCH') {
          console.log(`Error killing MCP server process ${this.serverProcessPid}: ${error}`)
        }
      }
      this.serverProcessPid = null
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
      console.log(`[MCP] Server ${this.serverConfig.name} loaded ${tools.length} tools successfully`)
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
    if (this.serverConfig.url) {
      console.log(`[MCP] ${this.serverConfig.name}: building remote transport for ${this.serverConfig.url}`)
      this.transport = this.buildRemoteTransport()
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
      throw new Error(`MCP server ${this.serverConfig.name} has neither url nor command configured.`)
    }
    // Connect to the server with timeout and better error handling
    try {
      await this.connectWithTimeout(instance)

      // For remote transports, wire up reconnection on unexpected close
      if (this.serverConfig.url) {
        this.wireRemoteReconnect(instance)
      }

      // Store the process PID for manual cleanup if needed
      if (this.transport && 'pid' in this.transport) {
        this.serverProcessPid = (this.transport as any).pid
        console.log(`Successfully connected to MCP server ${this.serverConfig.name} (PID: ${this.serverProcessPid})`)
      } else {
        console.log(
          `Successfully connected to MCP server ${this.serverConfig.name}${
            this._usingLegacySSE ? ' (legacy SSE transport)' : ''
          }`
        )
      }

      // Reset reconnect counters on successful connection
      this._reconnectAttempts = 0
      this._reconnectDelay = 2000

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
   * Attempt to connect the client to this.transport within MCP_CONNECT_TIMEOUT ms.
   *
   * For remote transports (URL-based), if the initial StreamableHTTP connection fails with
   * a signal that the server doesn't support the modern protocol (HTTP 4xx, or specific
   * error messages), transparently falls back to legacy SSEClientTransport and retries.
   */
  private async connectWithTimeout(instance: Client): Promise<void> {
    const isRemote = !!this.serverConfig.url
    const timeout = this.serverConfig.oauth2 ? MCP_OAUTH_CONNECT_TIMEOUT : MCP_CONNECT_TIMEOUT

    const doConnect = async (): Promise<void> => {
      console.log(`[MCP] ${this.serverConfig.name}: connecting (timeout=${timeout}ms)...`)
      const connectPromise = instance.connect(this.transport!)
      let timeoutHandle: NodeJS.Timeout
      const timeoutPromise = new Promise<never>((_, reject) => {
        timeoutHandle = setTimeout(() => reject(new Error(`Connection timeout after ${timeout}ms`)), timeout)
      })
      try {
        await Promise.race([connectPromise, timeoutPromise])
      } finally {
        clearTimeout(timeoutHandle!)
      }
    }

    if (!isRemote) {
      // Stdio transport — no fallback needed
      await doConnect()
      return
    }

    // Remote transport: try StreamableHTTP first, fall back to legacy SSE on 4xx / unsupported
    try {
      await doConnect()
      this._usingLegacySSE = false
    } catch (streamableError) {
      const msg = streamableError instanceof Error ? streamableError.message : String(streamableError)

      // Check if the initial failure triggered an OAuth flow
      const authPending = this.oauthProvider?.waitForAuth()
      if (authPending) {
        console.log(`[MCP] ${this.serverConfig.name}: waiting for OAuth authorization to complete...`)
        await authPending // blocks until user completes the popup and handleCallback() resolves

        // Close the old transport — finishAuth() ran on it but the SDK needs a fresh connection
        try {
          await this.transport!.close()
        } catch {
          // Ignore close errors
        }

        // Rebuild transport with the freshly obtained tokens
        this.transport = this.buildRemoteTransport()
        this._usingLegacySSE = false
        console.log(`[MCP] ${this.serverConfig.name}: OAuth complete, retrying connection`)
        await doConnect()
        return
      }

      if (!this.shouldFallbackToSSE(msg)) {
        throw streamableError
      }

      console.warn(
        `[MCP] ${this.serverConfig.name}: StreamableHTTP connection failed (${msg}), ` +
          `falling back to legacy SSEClientTransport`
      )

      // Close the failed StreamableHTTP transport before switching
      try {
        await this.transport!.close()
      } catch {
        // Ignore close errors during fallback
      }

      // Build the legacy SSE transport, preserving auth if needed
      const url = new URL(this.serverConfig.url!)
      if (this.oauthProvider) {
        this.transport = new SSEClientTransport(url, { authProvider: this.oauthProvider })
      } else if (this.serverConfig.authToken) {
        this.transport = new SSEClientTransport(url, {
          requestInit: { headers: { Authorization: `Bearer ${this.serverConfig.authToken}` } },
        })
      } else {
        this.transport = new SSEClientTransport(url)
      }

      this._usingLegacySSE = true
      console.log(`[MCP] ${this.serverConfig.name}: retrying with legacy SSE transport`)
      await doConnect()
    }
  }

  /**
   * Decide whether a StreamableHTTP connection error warrants a fallback to legacy SSE.
   * We fall back on HTTP 4xx responses and specific "not supported" messages.
   */
  private shouldFallbackToSSE(errorMessage: string): boolean {
    // HTTP 4xx status codes (the SDK includes the status code in the error message)
    if (/\b4\d{2}\b/.test(errorMessage)) return true
    // Explicit "not supported" or "method not allowed" wording
    if (/not supported|method not allowed|unsupported/i.test(errorMessage)) return true
    return false
  }

  /**
   * Wire the transport's onclose handler so that unexpected disconnects trigger a
   * reconnection attempt with exponential backoff.
   *
   * Only active for remote (URL-based) transports and only when kill() has not been called.
   */
  private wireRemoteReconnect(instance: Client): void {
    if (!this.transport) return

    const MAX_ATTEMPTS = 5
    const MAX_DELAY = 30000 // 30 s

    this.transport.onclose = () => {
      if (this._killed) {
        console.log(`[MCP] ${this.serverConfig.name}: transport closed (killed, no reconnect)`)
        return
      }

      if (this._reconnectAttempts >= MAX_ATTEMPTS) {
        console.error(
          `[MCP] ${this.serverConfig.name}: transport closed after ${MAX_ATTEMPTS} reconnect attempts — giving up`
        )
        return
      }

      const attempt = ++this._reconnectAttempts
      const delay = this._reconnectDelay
      this._reconnectDelay = Math.min(this._reconnectDelay * 2, MAX_DELAY)

      console.warn(
        `[MCP] ${this.serverConfig.name}: transport closed unexpectedly — reconnecting in ${delay}ms ` +
          `(attempt ${attempt}/${MAX_ATTEMPTS})`
      )

      setTimeout(async () => {
        if (this._killed) {
          console.log(`[MCP] ${this.serverConfig.name}: reconnect cancelled (killed)`)
          return
        }

        console.log(`[MCP] ${this.serverConfig.name}: attempting reconnect #${attempt}...`)

        // Reset promises so buildTools() will reinitialise on the next call
        this.clientPromise = undefined
        this.toolsPromise = undefined
        this.tools = []
        this.errorLogged = false

        // Rebuild the transport for a fresh connection
        this._usingLegacySSE = false
        this.transport = this.buildRemoteTransport()

        try {
          await this.connectWithTimeout(instance)
          this.wireRemoteReconnect(instance)
          // Restore the client promise so subsequent buildTools() calls reuse this instance
          this.clientPromise = Promise.resolve(instance)
          console.log(`[MCP] ${this.serverConfig.name}: reconnected successfully (attempt ${attempt})`)
          // Reset backoff on success
          this._reconnectAttempts = 0
          this._reconnectDelay = 2000
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err)
          console.error(`[MCP] ${this.serverConfig.name}: reconnect #${attempt} failed: ${msg}`)
          // The next onclose (or this close path) will schedule the following attempt
          // only if the transport fired onclose again; otherwise we stop here.
        }
      }, delay)
    }
  }

  /**
   * Build the transport for a remote (HTTP/SSE) MCP server.
   *
   * Priority:
   * 1. oauth2: true  -> StreamableHTTPClientTransport + McpOAuthProvider (full OAuth 2.1)
   * 2. authToken set -> StreamableHTTPClientTransport with static Bearer header
   * 3. otherwise     -> unauthenticated StreamableHTTPClientTransport
   *
   * Each case falls back to SSEClientTransport if the Streamable HTTP constructor throws
   * (e.g. server only supports legacy SSE transport).
   */
  private buildRemoteTransport(): Transport {
    const url = new URL(this.serverConfig.url!)

    if (this.serverConfig.oauth2) {
      if (!this.mcpInteractor || !this.mcpUserService || !this.mcpProjectName) {
        throw new Error(
          `MCP server ${this.serverConfig.name}: oauth2 requires interactor, userService and projectName — ` +
            `pass them to McpToolsFactory constructor`
        )
      }
      const redirectUri = `${this.mcpBaseUrl ?? 'http://localhost:3000'}/oauth/callback`
      console.log(`[MCP] ${this.serverConfig.name}: using OAuth 2.1 transport (redirectUri=${redirectUri})`)
      this.oauthProvider = new McpOAuthProvider(
        this.mcpInteractor,
        this.mcpUserService,
        this.mcpProjectName,
        this.serverConfig.id,
        redirectUri,
        this.serverConfig.oauthClientId,
        this.serverConfig.oauthClientSecret,
        this.serverConfig.oauthScope
      )
      console.log(
        `[MCP] ${this.serverConfig.name}: McpOAuthProvider created for mcpId=${this.serverConfig.id}${this.serverConfig.oauthClientId ? ` (static client_id=${this.serverConfig.oauthClientId})` : ' (dynamic registration)'}`
      )
      const transport = new StreamableHTTPClientTransport(url, { authProvider: this.oauthProvider })
      // Wire finishAuth so handleCallback() can complete the code exchange
      this.oauthProvider.setFinishAuth((code) => transport.finishAuth(code))
      console.log(`[MCP] ${this.serverConfig.name}: OAuth transport ready`)
      return transport
    }

    if (this.serverConfig.authToken) {
      const requestInit: RequestInit = {
        headers: { Authorization: `Bearer ${this.serverConfig.authToken}` },
      }
      console.log(`[MCP] ${this.serverConfig.name}: using static Bearer token`)
      return new StreamableHTTPClientTransport(url, { requestInit })
    }

    console.log(`[MCP] ${this.serverConfig.name}: connecting without authentication`)
    return new StreamableHTTPClientTransport(url)
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
    console.log(`[MCP] ${this.serverConfig.name}: listing resource templates...`)
    try {
      const result = await client.listResourceTemplates()
      if (result && result.templates && Array.isArray(result.templates)) {
        console.log(`[MCP] ${this.serverConfig.name}: found ${result.templates.length} resource template(s)`)
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
    console.log(`[MCP] ${this.serverConfig.name}: listing tools...`)
    try {
      const result = await client.listTools()
      if (result && result.tools && Array.isArray(result.tools)) {
        console.log(
          `[MCP] ${this.serverConfig.name}: found ${result.tools.length} tool(s): ${result.tools.map((t: any) => t.name).join(', ')}`
        )
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
