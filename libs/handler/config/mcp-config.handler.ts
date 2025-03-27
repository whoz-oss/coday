import { Interactor, NestedHandler, CommandContext } from '../../model'
import { McpServerConfig } from '../../model/user-config'
import { UserService } from '../../service/user.service'
import { keywords } from '../../keywords'

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
    const subCommand = parts[0] === 'mcp' && parts.length > 1 ? parts[1] : parts[0]

    switch (subCommand) {
      case 'list':
        await this.list()
        break
      case 'add':
        if (parts.length > 2) {
          // Command-line arguments provided
          await this.add(this.parseAddArgs(parts.slice(1)))
        } else {
          // Interactive mode
          await this.add()
        }
        break
      case 'remove':
      case 'rm':
        if (parts.length < 2 || (parts[0] === 'mcp' && parts.length < 3)) {
          const serverId = await this.promptForServerId('remove')
          if (serverId) {
            await this.remove(serverId)
          }
        } else {
          const serverIdIndex = parts[0] === 'mcp' ? 2 : 1
          await this.remove(parts[serverIdIndex])
        }
        break
      case 'enable':
        if (parts.length < 2 || (parts[0] === 'mcp' && parts.length < 3)) {
          const serverId = await this.promptForServerId('enable')
          if (serverId) {
            await this.setEnabled(serverId, true)
          }
        } else {
          const serverIdIndex = parts[0] === 'mcp' ? 2 : 1
          await this.setEnabled(parts[serverIdIndex], true)
        }
        break
      case 'disable':
        if (parts.length < 2 || (parts[0] === 'mcp' && parts.length < 3)) {
          const serverId = await this.promptForServerId('disable')
          if (serverId) {
            await this.setEnabled(serverId, false)
          }
        } else {
          const serverIdIndex = parts[0] === 'mcp' ? 2 : 1
          await this.setEnabled(parts[serverIdIndex], false)
        }
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
    this.interactor.displayText('  config mcp add                    - Interactively add or update an MCP server')
    this.interactor.displayText(
      '  config mcp add --id ID --name NAME --command CMD [--args ARG1 ARG2...] [--env KEY=VALUE...] [--cwd DIR] [--enabled true|false]'
    )
    this.interactor.displayText('                                    - Add or update an MCP server with command-line arguments')
    this.interactor.displayText('  config mcp remove|rm [ID]         - Remove an MCP server (interactive if ID not provided)')
    this.interactor.displayText('  config mcp enable [ID]            - Enable an MCP server (interactive if ID not provided)')
    this.interactor.displayText('  config mcp disable [ID]           - Disable an MCP server (interactive if ID not provided)')
    this.interactor.displayText('  config mcp help                   - Display this help message')
    this.interactor.displayText('')
    this.interactor.displayText('Note: Currently only local command-based MCP servers are supported.')
    this.interactor.displayText('Remote HTTP/HTTPS servers will be supported in a future update.')
    this.interactor.displayText('')
    this.interactor.displayText('Examples:')
    this.interactor.displayText('  ## Interactively configure an MCP server')
    this.interactor.displayText('config mcp add')
    this.interactor.displayText('')
    this.interactor.displayText('  ## Add a local python fetch MCP server with command-line arguments')
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
  async add(config: Partial<McpServerConfig> = {}): Promise<void> {
    const existingServers = this.userService.config.mcp?.servers || []
    const isUpdate = config.id && existingServers.some(s => s.id === config.id)
    const existingConfig = config.id ? existingServers.find(s => s.id === config.id) : undefined

    // Check if this is a non-interactive call with command line parameters
    const isNonInteractive = config.id && config.name && (config.command || config.url);
    
    if (!isNonInteractive) {
      // Interactive mode - prompt for server ID if not provided
      if (!config.id) {
        config.id = await this.interactor.promptText('Server ID (required)', existingConfig?.id)
        
        if (!config.id) {
          this.interactor.error('Server ID is required')
          return
        }
      }

      // Check for existing server with this ID
      const existingIndex = existingServers.findIndex((s) => s.id === config.id)
      if (existingIndex >= 0 && !isUpdate) {
        const overwriteAnswer = await this.interactor.promptText(
          `Server ID "${config.id}" already exists. Do you want to update it? (y/n)`,
          'y'
        )
        
        if (overwriteAnswer.toLowerCase() !== 'y') {
          this.interactor.displayText('Operation canceled.')
          return
        }
      }

      // Prompt for server name if not provided
      if (!config.name) {
        config.name = await this.interactor.promptText(
          'Server name (required)', 
          existingConfig?.name
        )
        
        if (!config.name) {
          this.interactor.error('Server name is required')
          return
        }
      }

      // Determine server type based on existing configuration or prompt user
      let serverType: string
      
      if (config.url) {
        serverType = 'url'
      } else if (config.command) {
        serverType = 'local'
      } else {
        serverType = await this.interactor.chooseOption(
          ['local', 'url', 'cancel'],
          'Server type',
          'Select server type (only local command-based servers are currently supported):'
        )
      }

      if (serverType === 'cancel') {
        this.interactor.displayText('Operation canceled.')
        return
      }

      if (serverType === 'url') {
        this.interactor.warn('Remote HTTP/HTTPS MCP servers are not currently supported. Please use local command-based servers instead.')
        const proceedAnswer = await this.interactor.promptText(
          'Do you want to proceed with configuring a URL-based server anyway? (y/n)',
          'n'
        )
        
        if (proceedAnswer.toLowerCase() !== 'y') {
          this.interactor.displayText('Operation canceled.')
          return
        }

        // Prompt for URL
        config.url = await this.interactor.promptText(
          'Server URL',
          config.url || existingConfig?.url
        )
        config.command = undefined
        config.args = undefined
        config.env = undefined
        config.cwd = undefined
      } else {
        // Prompt for command
        config.command = await this.interactor.promptText(
          'Command (required)',
          config.command || existingConfig?.command
        )
        
        if (!config.command) {
          this.interactor.error('Command is required for local servers')
          return
        }

        // Prompt for args if not already provided
        if (!config.args) {
          const argsString = await this.interactor.promptText(
            'Command arguments (space-separated)',
            existingConfig?.args?.join(' ')
          )
          
          if (argsString) {
            config.args = argsString.split(' ').filter(arg => arg.trim() !== '')
          }
        }

        // Prompt for working directory if not already provided
        if (!config.cwd) {
          config.cwd = await this.interactor.promptText(
            'Working directory (optional)',
            existingConfig?.cwd
          )
        }

        // Prompt for environment variables if not already provided
        if (!config.env) {
          const addEnvVars = await this.interactor.promptText(
            'Would you like to add environment variables? (y/n)',
            existingConfig?.env && Object.keys(existingConfig.env).length > 0 ? 'y' : 'n'
          )
          
          if (addEnvVars.toLowerCase() === 'y') {
            const env: Record<string, string> = {}
            let addingEnvVars = true
            
            // Show existing env vars if updating
            if (existingConfig?.env && Object.keys(existingConfig.env).length > 0) {
              this.interactor.displayText('Current environment variables:')
              for (const [key, value] of Object.entries(existingConfig.env)) {
                this.interactor.displayText(`  ${key}=${value}`)
              }
            }
            
            while (addingEnvVars) {
              const envKey = await this.interactor.promptText(
                'Environment variable key (or leave empty to finish)'
              )
              
              if (!envKey) {
                addingEnvVars = false
                continue
              }
              
              const envValue = await this.interactor.promptText(
                `Value for ${envKey}`
              )
              
              if (envValue) {
                env[envKey] = envValue
              }
            }
            
            if (Object.keys(env).length > 0) {
              config.env = env
            }
          }
        }

        config.url = undefined
      }

      // Prompt for auth token if not already provided
      if (config.authToken === undefined) {
        const useAuth = await this.interactor.promptText(
          'Does this server require authentication? (y/n)',
          existingConfig?.authToken ? 'y' : 'n'
        )
        
        if (useAuth.toLowerCase() === 'y') {
          const MASKED_VALUE = '********'
          const authPrompt = existingConfig?.authToken
            ? `Authentication token (Current value is masked)`
            : 'Authentication token'
          
          const authInput = await this.interactor.promptText(
            authPrompt,
            existingConfig?.authToken ? MASKED_VALUE : undefined
          )
          
          if (authInput && authInput !== MASKED_VALUE) {
            config.authToken = authInput
          } else if (authInput === MASKED_VALUE) {
            config.authToken = existingConfig?.authToken
          }
        } else {
          config.authToken = undefined
        }
      }

      // Prompt for enabled status if not already provided
      if (config.enabled === undefined) {
        const enabledAnswer = await this.interactor.promptText(
          'Enable this server? (y/n)',
          existingConfig?.enabled === false ? 'n' : 'y'
        )
        
        config.enabled = enabledAnswer.toLowerCase() === 'y'
      }
    } else {
      // Non-interactive mode - Validate required fields
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

      // For now, validate that either URL or command is provided (command is preferred)
      if (!config.command && !config.url) {
        this.interactor.error('Either command or URL is required.')
        this.displayHelp()
        return
      }
    }

    // Validate transport configuration (can't have both URL and command)
    if (config.url && config.command) {
      this.interactor.error(
        'Cannot specify both URL and command. Please configure either a URL-based or command-based server.'
      )
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
    const existingServerIndex = servers.findIndex((s) => s.id === serverConfig.id)
    if (existingServerIndex >= 0) {
      servers[existingServerIndex] = serverConfig
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

    // Confirm removal
    const confirmAnswer = await this.interactor.promptText(
      `Are you sure you want to remove MCP server "${serverName}" (${serverId})? (y/n)`,
      'y'
    )
    
    if (confirmAnswer.toLowerCase() !== 'y') {
      this.interactor.displayText('Operation canceled.')
      return
    }

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

    const serverName = servers[existingIndex].name
    const action = enabled ? 'enable' : 'disable'
    
    // Confirm action if server is already in the desired state
    if (servers[existingIndex].enabled === enabled) {
      this.interactor.displayText(`MCP server "${serverName}" is already ${enabled ? 'enabled' : 'disabled'}.`)
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
    this.interactor.displayText(`MCP server "${serverName}" is now ${status}`)
  }
  
  /**
   * Helper method to prompt for server ID from available servers
   */
  private async promptForServerId(action: string): Promise<string | undefined> {
    const servers = this.userService.config.mcp?.servers || []
    
    if (servers.length === 0) {
      this.interactor.displayText('No MCP servers configured.')
      return undefined
    }
    
    // Create options for selection with descriptive labels
    const options = servers.map(server => {
      const status = server.enabled ? 'enabled' : 'disabled'
      return { 
        id: server.id, 
        label: `${server.name} (${server.id}) - ${status}` 
      }
    })
    
    // Add cancel option
    options.push({ id: keywords.exit, label: 'Cancel' })
    
    // Prompt user to select a server
    const answer = await this.interactor.chooseOption(
      options.map(o => o.label),
      `Select MCP server to ${action}`,
      `Choose the MCP server you want to ${action}:`
    )
    
    // Find selected option
    const selectedOption = options.find(o => o.label === answer)
    
    if (!selectedOption || selectedOption.id === keywords.exit) {
      this.interactor.displayText('Operation canceled.')
      return undefined
    }
    
    return selectedOption.id
  }
}
