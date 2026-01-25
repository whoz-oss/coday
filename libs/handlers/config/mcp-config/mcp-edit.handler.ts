import { CommandContext, CommandHandler, Interactor } from '@coday/model'
import { McpConfigService } from '@coday/service/mcp-config.service'
import { McpServerConfig } from '@coday/model/mcp-server-config'
import { ConfigLevel } from '@coday/model/config-level'
import { parseArgs } from '../../parse-args'
import { cleanServerConfig, formatMcpConfig, getMcpConfigNameAndId, sanitizeMcpServerConfig } from './helpers'

export class McpEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private service: McpConfigService
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit an MCP server configuration. Use --project/-p for project level.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse arguments using parseArgs
    const args = parseArgs(this.getSubCommand(command), [{ key: 'project', alias: 'p' }])

    const isProjectLevel = !!args.project
    const targetLevel = isProjectLevel ? ConfigLevel.PROJECT : ConfigLevel.USER

    // Extract server name/id from remaining arguments
    const searchTerm = args.rest.trim().toLowerCase()

    // Get available configurations for selection
    const availableConfigs = this.getAvailableConfigs(targetLevel, searchTerm)

    if (availableConfigs.length === 0) {
      this.interactor.displayText('No MCP configurations found to edit.')
      return context
    }

    // Select configuration to edit
    const selectedConfig = await this.selectConfigToEdit(availableConfigs)
    if (!selectedConfig) {
      this.interactor.displayText('No configuration selected.')
      return context
    }

    // Determine if we need to clone or direct edit
    const configToEdit = await this.prepareConfigForEditing(selectedConfig, targetLevel)

    // Edit the configuration with merged context
    const editedConfig = await this.editConfigWithContext(configToEdit, targetLevel)

    // Save the configuration
    await this.service.saveMcpServer(editedConfig, targetLevel)

    // Display the updated config
    const updatedSanitized = sanitizeMcpServerConfig(editedConfig)
    const successMessage = `
# ✅ Updated MCP Configuration

${formatMcpConfig(updatedSanitized)}
`
    this.interactor.displayText(successMessage)

    return context
  }

  /**
   * Get available configurations for editing based on hierarchy rules
   */
  private getAvailableConfigs(
    targetLevel: ConfigLevel,
    searchTerm: string
  ): Array<{
    config: McpServerConfig
    sourceLevel: ConfigLevel
  }> {
    const available: Array<{ config: McpServerConfig; sourceLevel: ConfigLevel }> = []

    // Current level configs (direct edit)
    const currentLevelConfigs = this.service.getMcpServers(targetLevel)
    currentLevelConfigs
      .filter(
        (config) =>
          !searchTerm || config.name.toLowerCase().includes(searchTerm) || config.id.toLowerCase().includes(searchTerm)
      )
      .forEach((config) => available.push({ config, sourceLevel: targetLevel }))

    // Lower level configs (cloning allowed)
    const lowerLevels =
      targetLevel === ConfigLevel.USER ? [ConfigLevel.PROJECT, ConfigLevel.CODAY] : [ConfigLevel.CODAY]

    for (const level of lowerLevels) {
      const levelConfigs = this.service.getMcpServers(level)
      levelConfigs
        .filter((config) => {
          // Only include if not already overridden at target level
          const alreadyOverridden = currentLevelConfigs.some((c) => c.id === config.id)
          const matchesSearch =
            !searchTerm ||
            config.name.toLowerCase().includes(searchTerm) ||
            config.id.toLowerCase().includes(searchTerm)
          return !alreadyOverridden && matchesSearch
        })
        .forEach((config) => available.push({ config, sourceLevel: level }))
    }

    return available
  }

  /**
   * Let user select which configuration to edit
   */
  private async selectConfigToEdit(
    availableConfigs: Array<{
      config: McpServerConfig
      sourceLevel: ConfigLevel
    }>
  ): Promise<{ config: McpServerConfig; sourceLevel: ConfigLevel } | undefined> {
    if (availableConfigs.length === 1) {
      return availableConfigs[0]
    }

    // Create display options with level indicators
    const options = availableConfigs.map(
      ({ config, sourceLevel }) => `${getMcpConfigNameAndId(config)} (${sourceLevel.toLowerCase()} level)`
    )

    const selectedOption = await this.interactor.chooseOption(options, 'Select MCP configuration to edit:')

    const selectedIndex = options.indexOf(selectedOption)
    return selectedIndex >= 0 ? availableConfigs[selectedIndex] : undefined
  }

  /**
   * Prepare configuration for editing (clone if needed)
   */
  private async prepareConfigForEditing(
    selected: { config: McpServerConfig; sourceLevel: ConfigLevel },
    targetLevel: ConfigLevel
  ): Promise<McpServerConfig> {
    if (selected.sourceLevel === targetLevel) {
      // Direct edit
      const message = `
# Editing Configuration

**Server:** ${selected.config.name} (${selected.config.id})
**Level:** ${targetLevel.toLowerCase()}
**Action:** Direct edit
`
      this.interactor.displayText(message)
      return selected.config
    } else {
      // Clone from lower level
      const message = `
# Cloning Configuration

**Server:** "${selected.config.name}"
**From:** ${selected.sourceLevel.toLowerCase()} level
**To:** ${targetLevel.toLowerCase()} level
**Action:** Creating ${targetLevel.toLowerCase()}-level override
`
      this.interactor.displayText(message)

      // Create minimal clone (ID and name only)
      const clonedConfig: McpServerConfig = {
        id: selected.config.id,
        name: selected.config.name,
        enabled: true,
        args: [],
      }

      return clonedConfig
    }
  }

  /**
   * Edit configuration with merged context display
   */
  private async editConfigWithContext(config: McpServerConfig, targetLevel: ConfigLevel): Promise<McpServerConfig> {
    // Get merged view for context
    const mergedConfig = this.getMergedConfigById(config.id)

    // Check if this is a newly created config (from add handler delegation)
    // If the config has minimal content (empty command and empty args), it's likely newly created
    const isNewlyCreated =
      !config.command && (!config.args || config.args.length === 0) && !config.url && !config.cwd && !config.env

    if (isNewlyCreated) {
      // For newly created configs, ID and name are fixed - show them but don't edit
      const fixedMessage = `
## Fixed Configuration

**ID:** \`${config.id}\` (fixed)
**Name:** \`${config.name}\` (fixed)

These values are fixed for new configurations. Continuing with other settings...
`
      this.interactor.displayText(fixedMessage)
    } else {
      // For existing configs, allow editing ID and name
      config.id = await this.editPropertyWithContext('ID', 'id', config.id, mergedConfig, targetLevel)
      config.name = await this.editPropertyWithContext('Name', 'name', config.name, mergedConfig, targetLevel)
    }

    // Transport type selection
    const hasUrl = config.url || mergedConfig?.url
    const hasCommand = config.command || mergedConfig?.command

    if (!hasUrl && !hasCommand) {
      const transportType = await this.interactor.chooseOption(['command', 'url'], 'Select transport type:', 'command')

      if (transportType === 'url') {
        config.url = await this.editPropertyWithContext('URL', 'url', config.url, mergedConfig, targetLevel)
      } else {
        config.command = await this.editPropertyWithContext(
          'Command',
          'command',
          config.command,
          mergedConfig,
          targetLevel
        )
      }
    } else {
      config.url = await this.editPropertyWithContext('URL', 'url', config.url, mergedConfig, targetLevel)
      config.command = await this.editPropertyWithContext(
        'Command',
        'command',
        config.command,
        mergedConfig,
        targetLevel
      )
    }

    // Args (special handling for array)
    config.args = await this.editArgsWithContext(config.args, mergedConfig, targetLevel)

    // Environment variables (special handling for object)
    config.env = await this.editEnvWithContext(config.env, mergedConfig, targetLevel)

    config.cwd = await this.editPropertyWithContext('Working Directory', 'cwd', config.cwd, mergedConfig, targetLevel)

    // Auth token (special handling for sensitive data)
    config.authToken = await this.editAuthTokenWithContext(config.authToken, mergedConfig, targetLevel)

    // Boolean properties
    config.enabled = await this.editBooleanWithContext('Enabled', 'enabled', config.enabled, mergedConfig, targetLevel)
    config.debug = await this.editBooleanWithContext('Debug', 'debug', config.debug, mergedConfig, targetLevel)

    // Allowed tools (special handling for array)
    config.allowedTools = await this.editAllowedToolsWithContext(config.allowedTools, mergedConfig, targetLevel)

    // Clean the config
    cleanServerConfig(config)

    return config
  }

  /**
   * Edit a simple property with level context
   */
  private async editPropertyWithContext(
    displayName: string,
    property: keyof McpServerConfig,
    currentValue: any,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel
  ): Promise<any> {
    const contextDisplay = this.buildPropertyContext(property, mergedConfig, targetLevel)
    const prompt = `## ${displayName}

${contextDisplay}

Enter ${displayName.toLowerCase()}:`
    return await this.interactor.promptText(prompt, currentValue || '')
  }

  /**
   * Edit args with context
   */
  private async editArgsWithContext(
    currentArgs: string[] | undefined,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel
  ): Promise<string[]> {
    const contextDisplay = this.buildPropertyContext('args', mergedConfig, targetLevel)
    const prompt = `## Arguments

${contextDisplay}

Enter arguments (space-separated):`

    const argsString = await this.interactor.promptText(prompt, (currentArgs || []).join(' '))

    return argsString.trim() ? argsString.trim().split(' ') : []
  }

  /**
   * Edit environment variables with context
   */
  private async editEnvWithContext(
    currentEnv: Record<string, string> | undefined,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel
  ): Promise<Record<string, string> | undefined> {
    const contextDisplay = this.buildPropertyContext('env', mergedConfig, targetLevel)
    const envString = currentEnv
      ? Object.entries(currentEnv)
          .map(([key, value]) => `${key}=${value}`)
          .join(' ')
      : ''

    const prompt = `## Environment Variables

${contextDisplay}

Enter environment variables (format: KEY1=VALUE1 KEY2=VALUE2):`

    const newEnvString = await this.interactor.promptText(prompt, envString)

    if (!newEnvString.trim()) {
      return undefined
    }

    const env: Record<string, string> = {}
    newEnvString.split(' ').forEach((pair) => {
      const [key, value] = pair.split('=')
      if (key && value) {
        env[key] = value
      }
    })

    return Object.keys(env).length > 0 ? env : undefined
  }

  /**
   * Edit auth token with context (sensitive data handling)
   */
  private async editAuthTokenWithContext(
    currentToken: string | undefined,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel
  ): Promise<string | undefined> {
    const contextDisplay = this.buildPropertyContext('authToken', mergedConfig, targetLevel, true)

    const promptText = currentToken
      ? 'Enter authentication token (current value is masked):'
      : 'Enter authentication token (leave empty if not needed):'

    const prompt = `## Authentication Token

${contextDisplay}

${promptText}`

    const tokenInput = await this.interactor.promptText(prompt, currentToken ? '*****' : '')

    if (tokenInput === '*****') {
      return currentToken // Keep existing
    } else if (tokenInput === '') {
      return undefined // Remove
    } else {
      return tokenInput // New value
    }
  }

  /**
   * Edit boolean property with context
   */
  private async editBooleanWithContext(
    displayName: string,
    property: keyof McpServerConfig,
    currentValue: boolean | undefined,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel
  ): Promise<boolean> {
    const contextDisplay = this.buildPropertyContext(property, mergedConfig, targetLevel)
    const prompt = `## ${displayName}

${contextDisplay}

Set ${displayName.toLowerCase()}:`

    const choice = await this.interactor.chooseOption(
      ['true', 'false'],
      prompt,
      currentValue !== undefined ? currentValue.toString() : 'true'
    )

    return choice === 'true'
  }

  /**
   * Edit allowed tools with context
   */
  private async editAllowedToolsWithContext(
    currentTools: string[] | undefined,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel
  ): Promise<string[] | undefined> {
    const contextDisplay = this.buildPropertyContext('allowedTools', mergedConfig, targetLevel)
    const prompt = `## Allowed Tools

${contextDisplay}

Enter allowed tools (comma-separated, empty for all tools):`

    const toolsString = await this.interactor.promptText(prompt, (currentTools || []).join(', '))

    return toolsString.trim() ? toolsString.split(',').map((tool) => tool.trim()) : undefined
  }

  /**
   * Build property context display from all levels
   */
  private buildPropertyContext(
    property: keyof McpServerConfig,
    mergedConfig: McpServerConfig | null,
    targetLevel: ConfigLevel,
    isSensitive: boolean = false
  ): string {
    const levels = [ConfigLevel.CODAY, ConfigLevel.PROJECT, ConfigLevel.USER]
    const contextLines: string[] = []

    for (const level of levels) {
      const configs = this.service.getMcpServers(level)
      const config = configs.find((c) => c.id === mergedConfig?.id)
      const value = config?.[property]

      let displayValue: string
      if (value === undefined) {
        displayValue = '*[not set]*'
      } else if (isSensitive && value) {
        displayValue = '`*****`'
      } else if (Array.isArray(value)) {
        displayValue = `\`[${value.join(', ')}]\``
      } else if (typeof value === 'object' && value !== null) {
        displayValue = `\`{${Object.keys(value).join(', ')}}\``
      } else {
        displayValue = `\`${value.toString()}\``
      }

      const marker = level === targetLevel ? ' **← editing this level**' : ''
      contextLines.push(`- **${level}:** ${displayValue}${marker}`)
    }

    if (mergedConfig) {
      const mergedValue = mergedConfig[property]
      let displayMerged: string
      if (mergedValue === undefined) {
        displayMerged = '*[not set]*'
      } else if (isSensitive && mergedValue) {
        displayMerged = '`*****`'
      } else if (Array.isArray(mergedValue)) {
        displayMerged = `\`[${mergedValue.join(', ')}]\``
      } else if (typeof mergedValue === 'object' && mergedValue !== null) {
        displayMerged = `\`{${Object.keys(mergedValue).join(', ')}}\``
      } else {
        displayMerged = `\`${mergedValue.toString()}\``
      }

      contextLines.push(`- **MERGED:** ${displayMerged}`)
    }

    return contextLines.join('\n')
  }

  /**
   * Get merged configuration for a specific server ID
   */
  private getMergedConfigById(id: string): McpServerConfig | null {
    const merged = this.service.getMergedConfiguration()
    return merged.servers.find((s) => s.id === id) || null
  }
}
