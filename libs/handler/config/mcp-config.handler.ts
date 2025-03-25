import { Interactor, NestedHandler, CommandContext } from '../../model'
import { McpServerConfig } from '../../model/user-config'
import { UserService } from '../../service/user.service'

/**
 * Handler for MCP server configuration commands
 */
export class McpConfigHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    private userService: UserService
  ) {
    super(
      {
        commandWord: 'mcp',
        description: 'Configure MCP (Model Context Protocol) servers',
      },
      interactor
    )
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!command) {
      await this.list()
      return context
    }

    const parts = command.split(' ')
    const subCommand = parts[1]

    switch (subCommand) {
      case 'list':
        await this.list()
        break
      case 'add':
        await this.add(this.parseAddArgs(parts.slice(1)))
        break
      case 'remove':
      case 'rm':
        if (parts.length < 2) {
          this.interactor.error('Server ID is required')
          this.displayHelp()
          return context
        }
        await this.remove(parts[1])
        break
      case 'enable':
        if (parts.length < 2) {
          this.interactor.error('Server ID is required')
          this.displayHelp()
          return context
        }
        await this.setEnabled(parts[1], true)
        break
      case 'disable':
        if (parts.length < 2) {
          this.interactor.error('Server ID is required')
          this.displayHelp()
          return context
        }
        await this.setEnabled(parts[1], false)
        break
      case 'help':
        this.displayHelp()
        break
      default:
        this.interactor.error(`Unknown subcommand: ${subCommand}`)
        this.displayHelp()
    }

    return context
  }

  public displayHelp(): void {
    this.interactor.displayText('MCP Configuration Commands:')
    this.interactor.displayText('  config mcp list                   - List configured MCP servers')
    this.interactor.displayText(
      '  config mcp add --id ID --name NAME --command CMD [--args ARG1 ARG2...] [--env KEY=VALUE...] [--cwd DIR] [--enabled true|false]'
    )
    this.interactor.displayText('                                    - Add or update an MCP server')
    this.interactor.displayText('  config mcp remove|rm ID           - Remove an MCP server')
    this.interactor.displayText('  config mcp enable ID              - Enable an MCP server')
    this.interactor.displayText('  config mcp disable ID             - Disable an MCP server')
    this.interactor.displayText('  config mcp help                   - Display this help message')
    this.interactor.displayText('')
    this.interactor.displayText('Note: Currently only local command-based MCP servers are supported.')
    this.interactor.displayText('Remote HTTP/HTTPS servers will be supported in a future update.')
    this.interactor.displayText('')
    this.interactor.displayText('Examples:')
    this.interactor.displayText('  ## Add a local python fetch MCP server')
    this.interactor.displayText('config mcp add --id fetch --name "Fetch" --command uvx --args fetch-mcp-server')
    this.interactor.displayText('')
    this.interactor.displayText('  ## Add a local node filesystem MCP server with environment variables')
    this.interactor.displayText(
      'config mcp add --id file --name "File" --command npx --args -y "@modelcontextprotocol/server-filesystem" /Users/david/Desktop --env API_KEY=abc123'
    )
  }

  /**
   * Parse add command arguments
   */
  private parseAddArgs(args: string[]): Partial<McpServerConfig> {
    const config: Partial<McpServerConfig> = {}
    const env: Record<string, string> = {}
    let hasEnv = false

    for (let i = 0; i < args.length; i++) {
      const arg = args[i]

      if (arg === '--id' && i + 1 < args.length) {
        config.id = args[++i]
      } else if (arg === '--name' && i + 1 < args.length) {
        config.name = args[++i]
      } else if (arg === '--url' && i + 1 < args.length) {
        config.url = args[++i]
      } else if (arg === '--command' && i + 1 < args.length) {
        config.command = args[++i]
      } else if (arg === '--cwd' && i + 1 < args.length) {
        config.cwd = args[++i]
      } else if (arg === '--args') {
        // Collect all remaining args until the next flag
        const commandArgs: string[] = []
        while (i + 1 < args.length && !args[i + 1].startsWith('--')) {
          commandArgs.push(args[++i])
        }
        if (commandArgs.length > 0) {
          config.args = commandArgs
        }
      } else if (arg === '--env' && i + 1 < args.length) {
        // Parse environment variable in KEY=VALUE format
        const envVar = args[++i]
        const equalIndex = envVar.indexOf('=')

        if (equalIndex > 0) {
          const key = envVar.substring(0, equalIndex)
          const value = envVar.substring(equalIndex + 1)
          env[key] = value
          hasEnv = true
        } else {
          this.interactor.warn(`Invalid environment variable format: ${envVar}. Expected KEY=VALUE format.`)
        }
      } else if (arg === '--auth' && i + 1 < args.length) {
        config.authToken = args[++i]
      } else if (arg === '--enabled' && i + 1 < args.length) {
        config.enabled = args[++i].toLowerCase() === 'true'
      }
    }

    // Add environment variables if any were provided
    if (hasEnv) {
      config.env = env
    }

    return config
  }

  /**
   * List all configured MCP servers
   */
  async list(): Promise<void> {
    const servers = this.userService.config.mcp?.servers || []

    if (servers.length === 0) {
      this.interactor.displayText('No MCP servers configured.')
      this.interactor.displayText('\nTo add an MCP server, use:')
      this.interactor.displayText('  config mcp add --id ID --name NAME --url URL')
      this.interactor.displayText('\nFor example, to add the fetch MCP server:')
      this.interactor.displayText('  config mcp add --id fetch --name "Fetch MCP Server" --url http://localhost:3000')
      return
    }

    this.interactor.displayText('Configured MCP servers:')
    for (const server of servers) {
      const status = server.enabled ? 'enabled' : 'disabled'
      this.interactor.displayText(`- ${server.name} (${server.id}): ${status}`)
      if (server.url) {
        this.interactor.displayText(`  Type: Remote (HTTP) - Currently not supported`)
        this.interactor.displayText(`  URL: ${server.url}`)
        this.interactor.displayText(`  Note: Remote HTTP/HTTPS MCP servers are not currently supported.`)
        this.interactor.displayText(`        Use local command-based servers instead.`)
      } else if (server.command) {
        this.interactor.displayText(`  Type: Local (stdio)`)
        this.interactor.displayText(`  Command: ${server.command}`)
        if (server.args && server.args.length > 0) {
          this.interactor.displayText(`  Args: ${server.args.join(' ')}`)
        }
        if (server.env && Object.keys(server.env).length > 0) {
          this.interactor.displayText(`  Environment Variables:`)
          for (const [key, value] of Object.entries(server.env)) {
            this.interactor.displayText(`    ${key}=${value}`)
          }
        }
        if (server.cwd) {
          this.interactor.displayText(`  Working Directory: ${server.cwd}`)
        }
      } else {
        this.interactor.displayText(`  Type: Unknown (missing configuration)`)
        this.interactor.displayText(`  Note: Only local command-based MCP servers are currently supported.`)
      }
    }
  }

  /**
   * Add a new MCP server configuration
   */
  async add(config: Partial<McpServerConfig>): Promise<void> {
    if (!config.id) {
      this.interactor.error('Server ID is required')
      this.displayHelp()
      return
    }

    if (!config.name) {
      this.interactor.error('Server name is required')
      this.displayHelp()
      return
    }

    // Validate that command is specified (only local servers are supported for now)
    if (!config.command) {
      this.interactor.error('Command is required. Only local command-based MCP servers are currently supported.')
      this.displayHelp()
      return
    }

    // Validate transport configuration (can't have both URL and command)
    if (config.url && config.command) {
      this.interactor.error(
        'Cannot specify both URL and command. Currently only local command-based servers are supported.'
      )
      this.displayHelp()
      return
    }

    // Ensure config has all required fields
    const serverConfig: McpServerConfig = {
      id: config.id,
      name: config.name,
      url: config.url,
      command: config.command,
      args: config.args,
      env: config.env,
      cwd: config.cwd,
      authToken: config.authToken,
      enabled: config.enabled ?? true,
    }

    // Initialize MCP configuration if it doesn't exist
    if (!this.userService.config.mcp) {
      this.userService.config.mcp = { servers: [] }
    }

    // Initialize servers array if it doesn't exist
    if (!this.userService.config.mcp.servers) {
      this.userService.config.mcp.servers = []
    }

    // Get existing servers
    const servers = this.userService.config.mcp.servers

    // Check if server with this ID already exists
    const existingIndex = servers.findIndex((s) => s.id === serverConfig.id)
    if (existingIndex >= 0) {
      servers[existingIndex] = serverConfig
      this.interactor.displayText(`Updated MCP server: ${serverConfig.name}`)
    } else {
      servers.push(serverConfig)
      this.interactor.displayText(`Added MCP server: ${serverConfig.name}`)
    }

    // Save updated configuration
    this.userService.save()
  }

  /**
   * Remove an MCP server configuration
   */
  async remove(serverId: string): Promise<void> {
    // Get existing servers
    const servers = this.userService.config.mcp?.servers || []

    // Find server with this ID
    const existingIndex = servers.findIndex((s) => s.id === serverId)
    if (existingIndex < 0) {
      this.interactor.error(`MCP server not found: ${serverId}`)
      return
    }

    const serverName = servers[existingIndex].name

    // Remove server
    servers.splice(existingIndex, 1)

    // Save updated configuration
    if (this.userService.config.mcp) {
      this.userService.config.mcp.servers = servers
    }
    this.userService.save()

    this.interactor.displayText(`Removed MCP server: ${serverName} (${serverId})`)
  }

  /**
   * Enable or disable an MCP server
   */
  async setEnabled(serverId: string, enabled: boolean): Promise<void> {
    // Get existing servers
    const servers = this.userService.config.mcp?.servers || []

    // Find server with this ID
    const existingIndex = servers.findIndex((s) => s.id === serverId)
    if (existingIndex < 0) {
      this.interactor.error(`MCP server not found: ${serverId}`)
      return
    }

    // Update enabled status
    servers[existingIndex].enabled = enabled

    // Save updated configuration
    if (this.userService.config.mcp) {
      this.userService.config.mcp.servers = servers
    }
    this.userService.save()

    const status = enabled ? 'enabled' : 'disabled'
    this.interactor.displayText(`MCP server ${servers[existingIndex].name} is now ${status}`)
  }
}
