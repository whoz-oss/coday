import {CommandContext, Interactor, NestedHandler} from '../../../model'
import {McpServerConfig} from '../../../model/mcp-server-config'
import {CodayServices} from '../../../coday-services'
import {keywords} from '../../../keywords'
import {McpListHandler} from './mcp-list.handler'
import {McpEditHandler} from './mcp-edit.handler'
import {McpAddHandler} from './mcp-add.handler'
import {McpDeleteHandler} from './mcp-delete.handler'

/**
 * Handler for MCP server configuration commands
 */
export class McpConfigHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    private services: CodayServices
  ) {
    super(
      {
        commandWord: 'mcp',
        description: `Configure MCP (Model Context Protocol) servers`,
      },
      interactor
    )

    this.handlers = [
      new McpListHandler(interactor, services.mcp),
      new McpEditHandler(interactor, services.mcp),
      new McpAddHandler(interactor, services.mcp),
      new McpDeleteHandler(interactor, services.mcp),
    ]
  }

  async handle2(command: string, context: CommandContext): Promise<CommandContext> {
    if (!command) {
      await this.services.mcp.listServers()
      return context
    }
    let subCommand = this.getSubCommand(command)

    const parts = command.split(' ')

    // Check if we're configuring at project level
    let isProjectLevel = false
    const projectLevelFlags = ['--project', '-p']
    const flagIndex = parts.findIndex((part) => projectLevelFlags.includes(part))

    if (flagIndex >= 0) {
      isProjectLevel = true
      // Remove the project flag from parts
      parts.splice(flagIndex, 1)
      // Re-calculate subCommand in case flag was before it
      subCommand = parts[0] === 'mcp' && parts.length > 1 ? parts[1] : parts[0]
    }

    // Verify project is selected when operating at project level
    if (isProjectLevel && !this.services.project.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return context
    }

    switch (subCommand) {
      case 'list':
        await this.services.mcp.listServers(isProjectLevel)
        break
      case 'add':
        if (parts.length > 2) {
          // Command-line arguments provided
          await this.add(this.parseAddArgs(parts.slice(1)), isProjectLevel)
        } else {
          // Interactive mode
          await this.add(undefined, isProjectLevel)
        }
        break
      case 'remove':
      case 'rm':
        if (parts.length < 2 || (parts[0] === 'mcp' && parts.length < 3)) {
          const serverId = await this.promptForServerId('remove', isProjectLevel)
          if (serverId) {
            await this.services.mcp.removeServer(serverId, isProjectLevel)
          }
        } else {
          const serverIdIndex = parts[0] === 'mcp' ? 2 : 1
          await this.services.mcp.removeServer(parts[serverIdIndex], isProjectLevel)
        }
        break
      case 'enable':
        if (parts.length < 2 || (parts[0] === 'mcp' && parts.length < 3)) {
          const serverId = await this.promptForServerId('enable', isProjectLevel)
          if (serverId) {
            await this.services.mcp.setServerEnabled(serverId, true, isProjectLevel)
          }
        } else {
          const serverIdIndex = parts[0] === 'mcp' ? 2 : 1
          await this.services.mcp.setServerEnabled(parts[serverIdIndex], true, isProjectLevel)
        }
        break
      case 'disable':
        if (parts.length < 2 || (parts[0] === 'mcp' && parts.length < 3)) {
          const serverId = await this.promptForServerId('disable', isProjectLevel)
          if (serverId) {
            await this.services.mcp.setServerEnabled(serverId, false, isProjectLevel)
          }
        } else {
          const serverIdIndex = parts[0] === 'mcp' ? 2 : 1
          await this.services.mcp.setServerEnabled(parts[serverIdIndex], false, isProjectLevel)
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
    this.interactor.displayText('  config mcp list                   - List configured user-level MCP servers')
    this.interactor.displayText('  config mcp list --project         - List project-level MCP servers')
    this.interactor.displayText(
      '  config mcp add                    - Interactively add or update a user-level MCP server'
    )
    this.interactor.displayText(
      '  config mcp add --project          - Interactively add or update a project-level MCP server'
    )
    this.interactor.displayText(
      '  config mcp add --id ID --name NAME --command CMD [--args ARG1 ARG2...] [--env KEY=VALUE...] [--cwd DIR] [--enabled true|false]'
    )
    this.interactor.displayText(
      '                                    - Add or update a user-level MCP server with command-line arguments'
    )
    this.interactor.displayText(
      '  config mcp remove|rm [ID]         - Remove a user-level MCP server (interactive if ID not provided)'
    )
    this.interactor.displayText('  config mcp remove|rm [ID] --project - Remove a project-level MCP server')
    this.interactor.displayText(
      '  config mcp enable [ID]            - Enable a user-level MCP server (interactive if ID not provided)'
    )
    this.interactor.displayText('  config mcp enable [ID] --project  - Enable a project-level MCP server')
    this.interactor.displayText(
      '  config mcp disable [ID]           - Disable a user-level MCP server (interactive if ID not provided)'
    )
    this.interactor.displayText('  config mcp disable [ID] --project - Disable a project-level MCP server')
    this.interactor.displayText('  config mcp help                   - Display this help message')
    this.interactor.displayText('')
    this.interactor.displayText('Note: Currently only local command-based MCP servers are supported.')
    this.interactor.displayText('Remote HTTP/HTTPS servers will be supported in a future update.')
    this.interactor.displayText('')
    this.interactor.displayText('Examples:')
    this.interactor.displayText('  ## Interactively configure a user-level MCP server')
    this.interactor.displayText('config mcp add')
    this.interactor.displayText('')
    this.interactor.displayText('  ## Add a local python fetch MCP server with command-line arguments')
    this.interactor.displayText('config mcp add --id fetch --name "Fetch" --command uvx --args fetch-mcp-server')
    this.interactor.displayText('')
    this.interactor.displayText('  ## Add a project-level node filesystem MCP server')
    this.interactor.displayText(
      'config mcp add --project --id file --name "File" --command npx --args -y "@modelcontextprotocol/server-filesystem" src'
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
   * @param isProjectLevel Whether to list project-level servers instead of user-level
   */
  async list(isProjectLevel: boolean = false): Promise<void> {
    await this.services.mcp.listServers(isProjectLevel)
  }

  /**
   * Add a new MCP server configuration
   * @param config Initial configuration (can be partial)
   * @param isProjectLevel Whether to add to project config instead of user config
   */
  async add(config: Partial<McpServerConfig> = {}, isProjectLevel: boolean = false): Promise<void> {
    // Check if this is a non-interactive call with command line parameters
    const isNonInteractive = config.id && config.name && (config.command || config.url)

    if (!isNonInteractive) {
      // Handle interactive add through interactive prompts
      await this.interactiveAdd(config, isProjectLevel)
    } else {
      // Direct add with provided config
      await this.services.mcp.addServer(config, isProjectLevel)
    }
  }

  /**
   * Interactive server configuration
   */
  private async interactiveAdd(config: Partial<McpServerConfig> = {}, isProjectLevel: boolean): Promise<void> {
    const servers = this.services.mcp.getServers(isProjectLevel)
    const existingConfig = config.id ? servers.find((s) => s.id === config.id) : undefined

    // Interactive mode - prompt for server ID if not provided
    if (!config.id) {
      config.id = await this.interactor.promptText('Server ID (required)', existingConfig?.id)

      if (!config.id) {
        this.interactor.error('Server ID is required')
        return
      }
    }

    // Check for existing server with this ID
    const existingIndex = servers.findIndex((s) => s.id === config.id)
    if (existingIndex >= 0 && !existingConfig) {
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
      config.name = await this.interactor.promptText('Server name (required)', existingConfig?.name)

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

    // Configure server based on type
    await this.configureServerByType(config, serverType, existingConfig)

    // Save the server configuration
    await this.services.mcp.addServer(config, isProjectLevel)
  }

  /**
   * Configure server based on type (URL or local command)
   */
  private async configureServerByType(
    config: Partial<McpServerConfig>,
    serverType: string,
    existingConfig?: McpServerConfig
  ): Promise<void> {
    if (serverType === 'url') {
      this.interactor.warn(
        'Remote HTTP/HTTPS MCP servers are not currently supported. Please use local command-based servers instead.'
      )
      const proceedAnswer = await this.interactor.promptText(
        'Do you want to proceed with configuring a URL-based server anyway? (y/n)',
        'n'
      )

      if (proceedAnswer.toLowerCase() !== 'y') {
        this.interactor.displayText('Operation canceled.')
        throw new Error('Operation canceled')
      }

      // Prompt for URL
      config.url = await this.interactor.promptText('Server URL', config.url || existingConfig?.url)
      config.command = undefined
      config.args = undefined
      config.env = undefined
      config.cwd = undefined
    } else {
      // Prompt for command
      config.command = await this.interactor.promptText('Command (required)', config.command || existingConfig?.command)

      if (!config.command) {
        this.interactor.error('Command is required for local servers')
        throw new Error('Command is required')
      }

      // Prompt for args if not already provided
      if (!config.args) {
        const argsString = await this.interactor.promptText(
          'Command arguments (space-separated)',
          existingConfig?.args?.join(' ')
        )

        if (argsString) {
          config.args = argsString.split(' ').filter((arg) => arg.trim() !== '')
        }
      }

      // Prompt for working directory
      config.cwd = await this.interactor.promptText('Working directory (optional)', existingConfig?.cwd)

      // Prompt for environment variables
      await this.configureEnvironmentVariables(config, existingConfig)

      config.url = undefined
    }

    // Prompt for auth token
    await this.configureAuthToken(config, existingConfig)

    // Prompt for enabled status
    if (config.enabled === undefined) {
      const enabledAnswer = await this.interactor.promptText(
        'Enable this server? (y/n)',
        existingConfig?.enabled === false ? 'n' : 'y'
      )

      config.enabled = enabledAnswer.toLowerCase() === 'y'
    }
  }

  /**
   * Configure environment variables for a server
   */
  private async configureEnvironmentVariables(
    config: Partial<McpServerConfig>,
    existingConfig?: McpServerConfig
  ): Promise<void> {
    if (config.env) return // Already configured

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
        const envKey = await this.interactor.promptText('Environment variable key (or leave empty to finish)')

        if (!envKey) {
          addingEnvVars = false
          continue
        }

        const envValue = await this.interactor.promptText(`Value for ${envKey}`)

        if (envValue) {
          env[envKey] = envValue
        }
      }

      if (Object.keys(env).length > 0) {
        config.env = env
      }
    }
  }

  /**
   * Configure authentication token for a server
   */
  private async configureAuthToken(config: Partial<McpServerConfig>, existingConfig?: McpServerConfig): Promise<void> {
    if (config.authToken !== undefined) return // Already configured

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

  /**
   * Remove an MCP server configuration
   * @param serverId ID of server to remove
   * @param isProjectLevel Whether to remove from project config instead of user config
   */
  async remove(serverId: string, isProjectLevel: boolean = false): Promise<void> {
    await this.services.mcp.removeServer(serverId, isProjectLevel)
  }

  /**
   * Enable or disable an MCP server
   * @param serverId ID of server to enable/disable
   * @param enabled Whether to enable (true) or disable (false) the server
   * @param isProjectLevel Whether to modify project config instead of user config
   */
  async setEnabled(serverId: string, enabled: boolean, isProjectLevel: boolean = false): Promise<void> {
    await this.services.mcp.setServerEnabled(serverId, enabled, isProjectLevel)
  }

  /**
   * Helper method to prompt for server ID from available servers
   * @param action Action being performed (for prompt text)
   * @param isProjectLevel Whether to list project-level servers instead of user-level
   */
  private async promptForServerId(action: string, isProjectLevel: boolean = false): Promise<string | undefined> {
    const serverOptions = this.services.mcp.getServerSelectionOptions(isProjectLevel)

    if (serverOptions.length === 0) {
      this.interactor.displayText(`No ${isProjectLevel ? 'project-level' : 'user-level'} MCP servers configured.`)
      return undefined
    }

    // Create options for selection with descriptive labels
    const options = serverOptions.map((server) => {
      const status = server.enabled ? 'enabled' : 'disabled'
      return {
        id: server.id,
        label: `${server.name} (${server.id}) - ${status}`,
      }
    })

    // Add cancel option
    options.push({ id: keywords.exit, label: 'Cancel' })

    // Prompt user to select a server
    const answer = await this.interactor.chooseOption(
      options.map((o) => o.label),
      `Select ${isProjectLevel ? 'project-level' : 'user-level'} MCP server to ${action}`,
      `Choose the MCP server you want to ${action}:`
    )

    // Find selected option
    const selectedOption = options.find((o) => o.label === answer)

    if (!selectedOption || selectedOption.id === keywords.exit) {
      this.interactor.displayText('Operation canceled.')
      return undefined
    }

    return selectedOption.id
  }
}
