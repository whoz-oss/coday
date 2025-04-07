import { CommandContext, CommandHandler, Interactor } from '../../../model'
import { McpConfigService } from '../../../service/mcp-config.service'
import { McpServerConfig } from '../../../model/mcp-server-config'
import {
  cleanServerConfig,
  formatMcpConfig,
  getMcpConfigNameAndId,
  mcpServerConfigToArgs,
  sanitizeMcpServerConfig,
} from './helpers'

export class McpEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit a single mcp config to select.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommands = this.getSubCommand(command).split(' ')

    // check if project level or user level
    const isProjectLevel = subCommands.filter((c) => c === '--project' || c === '-p').length > 0

    // detect the name of the server or option
    const names = subCommands.filter((c) => !c.startsWith('-'))
    const name = names.length ? names[0].toLowerCase() : undefined

    const matching = this.service
      .getServers(isProjectLevel)
      .filter((s) => name && s.name.toLowerCase().startsWith(name))
    let serverConfig: McpServerConfig | undefined

    // if multiple, choose the one
    if (matching.length > 1) {
      const matchingPerNameAndId = new Map<string, McpServerConfig>(
        matching.map((server) => [getMcpConfigNameAndId(server), server])
      )
      const choice = await this.interactor.chooseOption(
        Array.from(matchingPerNameAndId.keys()).sort(),
        'Select the MCP config to edit'
      )
      serverConfig = matchingPerNameAndId.get(choice)
    }

    // if single server, selection is done
    if (matching.length === 1) {
      serverConfig = matching[0]
    }

    if (!serverConfig) {
      this.interactor.displayText('Could not select the MCP config to edit.')
      return context
    }

    let serverAtProjectLevel: McpServerConfig | undefined
    // if at user level, present the project level part and the user part, sanitized
    if (!isProjectLevel) {
      // then retrieve the server at project level and show it for context
      serverAtProjectLevel = this.service
        .getServers(true)
        .find((s) => s.name === serverConfig.name && s.id === serverConfig.id)
      if (serverAtProjectLevel) {
        const sanitized = sanitizeMcpServerConfig(serverAtProjectLevel)
        this.interactor.displayText(`Current MCP config at project level:
${formatMcpConfig(sanitized)}

Matching arguments:
${mcpServerConfigToArgs(sanitized)}

`)
      }
    }
    // once server to edit is selected, present the sanitized version
    const sanitized = sanitizeMcpServerConfig(serverConfig)
    this.interactor.displayText(`Current MCP config being edited:
${formatMcpConfig(sanitized)}

Matching arguments:
${mcpServerConfigToArgs(sanitized)}`)

    // then go through all properties of the McpServerConfig and edit them from their current value
    serverConfig.id = await this.interactor.promptText(`id of the mcp server`, serverConfig.id)
    serverConfig.name = await this.interactor.promptText(`name of the mcp server`, serverConfig.name)
    serverConfig.url = await this.interactor.promptText(`url of the mcp server`, serverConfig.url)
    serverConfig.command = await this.interactor.promptText(`command of the mcp server`, serverConfig.command)
    serverConfig.args = (
      await this.interactor.promptText(`args of the mcp server`, serverConfig.args?.join(' ') || '')
    ).split(' ')

    // Continue with other properties
    const envString = serverConfig.env
      ? Object.entries(serverConfig.env)
          .map(([key, value]) => `${key}=${value}`)
          .join(' ')
      : ''
    const newEnvString = await this.interactor.promptText(
      `env variables of the mcp server (format: KEY1=VALUE1 KEY2=VALUE2)`,
      envString
    )

    // Parse environment variables back into an object
    serverConfig.env = {}
    if (newEnvString.trim()) {
      newEnvString.split(' ').forEach((pair) => {
        const [key, value] = pair.split('=')
        if (key && value) {
          serverConfig.env![key] = value
        }
      })
    }

    serverConfig.cwd = await this.interactor.promptText(`working directory of the mcp server`, serverConfig.cwd || '')

    // Special handling for authToken - mask it if present
    const currentAuthToken = serverConfig.authToken
    const authTokenPrompt = currentAuthToken
      ? `authentication token of the mcp server (current value is masked)`
      : `authentication token of the mcp server`

    const authTokenInput = await this.interactor.promptText(authTokenPrompt, currentAuthToken ? '*****' : '')

    // Only update if not masked value
    if (authTokenInput === '*****') {
      // Keep existing authToken
    } else if (authTokenInput === '') {
      // Explicitly remove authToken
      serverConfig.authToken = undefined
    } else {
      // New authToken provided
      serverConfig.authToken = authTokenInput
    }

    const enabledChoice = await this.interactor.chooseOption(
      ['true', 'false'],
      `Is the server connection enabled?`,
      serverConfig.enabled ? 'true' : 'false'
    )
    serverConfig.enabled = enabledChoice === 'true'

    const allowedToolsStr = await this.interactor.promptText(
      `allowed tools (comma-separated list, empty for all tools)`,
      serverConfig.allowedTools?.join(',') || ''
    )
    serverConfig.allowedTools = allowedToolsStr ? allowedToolsStr.split(',').map((tool) => tool.trim()) : undefined

    // Clean the serverConfig by removing empty values
    cleanServerConfig(serverConfig)

    // Save the edited serverConfig
    await this.service.saveServer(serverConfig, isProjectLevel)

    // Display the updated config
    const updatedSanitized = sanitizeMcpServerConfig(serverConfig)
    this.interactor.displayText(
      `\nUpdated MCP config:\n${formatMcpConfig(updatedSanitized)}\n\nMatching arguments:\n${mcpServerConfigToArgs(updatedSanitized)}`
    )

    return context
  }
}
