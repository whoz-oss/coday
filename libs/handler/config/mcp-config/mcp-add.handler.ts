import { CommandContext, CommandHandler, Interactor } from '../../../model'
import { McpConfigService } from '../../../service/mcp-config.service'
import { McpServerConfig } from '../../../model/mcp-server-config'
import { cleanServerConfig, formatMcpConfig, mcpServerConfigToArgs, sanitizeMcpServerConfig } from './helpers'

// Using a simple function to generate IDs instead of the uuid package
function generateId(): string {
  return Math.random().toString(36).substring(2, 10)
}

export class McpAddHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'add',
      description: 'Add a new MCP server configuration.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommands = this.getSubCommand(command).split(' ')

    // Check if project level or user level
    const isProjectLevel = subCommands.filter((c) => c === '--project' || c === '-p').length > 0

    // Create a new server config with defaults
    const serverConfig: McpServerConfig = {
      id: '',
      name: '',
      enabled: true, // Enabled by default
      args: [],
      env: {},
    }

    // Display header information
    const levelName = isProjectLevel ? 'project' : 'user'
    this.interactor.displayText(`Adding a new ${levelName}-level MCP server configuration:`)
    this.interactor.displayText('Note: Either URL or command must be provided, but not both.')

    // Prompt for basic information
    serverConfig.id = await this.interactor.promptText('ID of the MCP server', serverConfig.id)
    serverConfig.name = await this.interactor.promptText('Name of the MCP server', '')

    // Validate name is provided
    if (!serverConfig.name) {
      this.interactor.error('Server name is required')
      return context
    }

    // Transport type
    const transportType = await this.interactor.chooseOption(
      ['command', 'url'],
      'Select transport type',
      'command' // Default to command as it's currently the only supported option
    )

    if (transportType === 'url') {
      serverConfig.url = await this.interactor.promptText('URL of the MCP server', '')
      this.interactor.warn(
        'Remote HTTP/HTTPS MCP servers are not currently supported. Using local command-based servers is recommended.'
      )
    } else {
      serverConfig.command = await this.interactor.promptText('Command to execute the MCP server', '')

      // Validate command is provided
      if (!serverConfig.command) {
        this.interactor.error('Command is required for command-based servers')
        return context
      }

      // Arguments
      const argsInput = await this.interactor.promptText('Arguments for the command (space-separated)', '')
      serverConfig.args = argsInput ? argsInput.split(' ') : []

      // Environment variables
      const envInput = await this.interactor.promptText('Environment variables (format: KEY1=VALUE1 KEY2=VALUE2)', '')

      // Parse environment variables
      serverConfig.env = {}
      if (envInput.trim()) {
        envInput.split(' ').forEach((pair) => {
          const [key, value] = pair.split('=')
          if (key && value) {
            serverConfig.env[key] = value
          }
        })
      }

      // Working directory
      serverConfig.cwd = await this.interactor.promptText('Working directory (leave empty for default)', '')
    }

    // Authentication token
    const authToken = await this.interactor.promptText('Authentication token (leave empty if not needed)', '')
    if (authToken) {
      serverConfig.authToken = authToken
    }

    // Enabled status
    const enabledChoice = await this.interactor.chooseOption(
      ['true', 'false'],
      'Enable this server?',
      'true' // Default to enabled
    )
    serverConfig.enabled = enabledChoice === 'true'

    // Allowed tools
    const allowedToolsStr = await this.interactor.promptText(
      'Allowed tools (comma-separated list, empty for all tools)',
      ''
    )
    serverConfig.allowedTools = allowedToolsStr ? allowedToolsStr.split(',').map((tool) => tool.trim()) : undefined

    // Clean the serverConfig by removing empty values
    cleanServerConfig(serverConfig)

    // Check if a server with the same ID already exists
    const existingServers = this.service.getServers(isProjectLevel)
    const existingServer = existingServers.find((s) => s.id === serverConfig.id)

    if (existingServer) {
      const confirmOverwrite = await this.interactor.promptText(
        `A server with ID "${serverConfig.id}" already exists. Overwrite? (y/n)`,
        'n'
      )

      if (confirmOverwrite.toLowerCase() !== 'y') {
        this.interactor.displayText('Operation canceled.')
        return context
      }
    }

    // Save the new server configuration
    await this.service.saveServer(serverConfig, isProjectLevel)

    // Display the created config
    const sanitized = sanitizeMcpServerConfig(serverConfig)
    this.interactor.displayText(
      `\nNew MCP server configuration added:\n${formatMcpConfig(sanitized)}\n\nMatching arguments:\n${mcpServerConfigToArgs(sanitized)}`
    )

    return context
  }
}
