import { Interactor } from '../model/interactor'
import { UserService } from './user.service'
import { ProjectService } from './project.service'
import { McpServerConfig } from '../model/mcp-server-config'

/**
 * Service for managing MCP (Model Context Protocol) server configurations
 * at both user and project levels.
 */
export class McpConfigService {
  constructor(
    private userService: UserService,
    private projectService: ProjectService,
    private interactor: Interactor
  ) {}

  /**
   * List all configured MCP servers at the specified level
   * @param isProjectLevel Whether to list project-level servers instead of user-level
   */
  async listServers(isProjectLevel: boolean = false): Promise<void> {
    const servers = this.getServers(isProjectLevel)
    const levelName = isProjectLevel ? 'project' : 'user'
    
    if (isProjectLevel && !this.projectService.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return
    }
    
    const projectName = isProjectLevel ? this.projectService.selectedProject?.name : undefined
    const headerText = projectName 
      ? `Project-level MCP servers for project: ${projectName}`
      : 'User-level MCP servers:'
    
    this.interactor.displayText(headerText)
    
    if (servers.length === 0) {
      this.interactor.displayText(`No ${levelName}-level MCP servers configured.`)
      this.interactor.displayText(`\nTo add a ${levelName}-level MCP server, use:`)
      this.interactor.displayText(`  config mcp add${isProjectLevel ? ' --project' : ''}`)
      return
    }

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
        if (server.allowedTools && server.allowedTools.length > 0) {
          this.interactor.displayText(`  Allowed Tools: ${server.allowedTools.join(', ')}`)
        }
      } else {
        this.interactor.displayText(`  Type: Unknown (missing configuration)`)
        this.interactor.displayText(`  Note: Only local command-based MCP servers are currently supported.`)
      }
    }
    
    // If showing user-level servers, mention project-level ones if they exist
    if (!isProjectLevel && this.hasProjectServers()) {
      this.interactor.displayText('\nNote: Project-level MCP servers are also configured for this project.')
      this.interactor.displayText('To view project-level servers, use: config mcp list --project')
    }
  }

  /**
   * Add or update an MCP server configuration
   * @param config Server configuration (partial)
   * @param isProjectLevel Whether to add to project config instead of user config
   */
  async addServer(config: Partial<McpServerConfig>, isProjectLevel: boolean = false): Promise<void> {
    if (isProjectLevel && !this.projectService.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return
    }
    
    // Get existing servers for the specified level
    const existingServers = this.getServers(isProjectLevel)
    
    // Determine if this is an update or a new server
    const isUpdate = config.id && existingServers.some(s => s.id === config.id)
    
    // Validate required fields
    if (!config.id) {
      this.interactor.error('Server ID is required')
      return
    }

    if (!config.name) {
      this.interactor.error('Server name is required')
      return
    }

    // Validate transport configuration (can't have both URL and command)
    if (config.url && config.command) {
      this.interactor.error(
        'Cannot specify both URL and command. Please configure either a URL-based or command-based server.'
      )
      return
    }
    
    // For now, validate that either URL or command is provided
    if (!config.command && !config.url) {
      this.interactor.error('Either command or URL is required.')
      return
    }
    
    // Warn about URL-based servers not being supported
    if (config.url) {
      this.interactor.warn('Remote HTTP/HTTPS MCP servers are not currently supported. Using local command-based servers is recommended.')
    }

    // Ensure config has all required fields by merging with existing or defaults
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
      allowedTools: config.allowedTools
    }

    // Save the configuration at the appropriate level
    await this.saveServer(serverConfig, isProjectLevel)
    
    const levelName = isProjectLevel ? 'project-level' : 'user-level'
    const actionName = isUpdate ? 'Updated' : 'Added'
    this.interactor.displayText(`${actionName} ${levelName} MCP server: ${serverConfig.name}`)
  }

  /**
   * Remove an MCP server configuration
   * @param serverId ID of the server to remove
   * @param isProjectLevel Whether to remove from project level instead of user level
   */
  async removeServer(serverId: string, isProjectLevel: boolean = false): Promise<void> {
    if (isProjectLevel && !this.projectService.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return
    }
    
    const servers = this.getServers(isProjectLevel)
    
    // Find server with this ID
    const existingIndex = servers.findIndex((s) => s.id === serverId)
    if (existingIndex < 0) {
      this.interactor.error(`MCP server not found: ${serverId}`)
      return
    }

    const serverName = servers[existingIndex].name
    const levelName = isProjectLevel ? 'project-level' : 'user-level'

    // Confirm removal
    const confirmAnswer = await this.interactor.promptText(
      `Are you sure you want to remove ${levelName} MCP server "${serverName}" (${serverId})? (y/n)`,
      'y'
    )
    
    if (confirmAnswer.toLowerCase() !== 'y') {
      this.interactor.displayText('Operation canceled.')
      return
    }

    // Remove server
    servers.splice(existingIndex, 1)

    // Save updated configuration
    await this.saveServers(servers, isProjectLevel)
    this.interactor.displayText(`Removed ${levelName} MCP server: ${serverName} (${serverId})`)
  }

  /**
   * Enable or disable an MCP server
   * @param serverId ID of the server to enable/disable
   * @param enabled Whether to enable (true) or disable (false) the server
   * @param isProjectLevel Whether to modify project config instead of user config
   */
  async setServerEnabled(serverId: string, enabled: boolean, isProjectLevel: boolean = false): Promise<void> {
    if (isProjectLevel && !this.projectService.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return
    }
    
    const servers = this.getServers(isProjectLevel)
    
    // Find server with this ID
    const existingIndex = servers.findIndex((s) => s.id === serverId)
    if (existingIndex < 0) {
      this.interactor.error(`MCP server not found: ${serverId}`)
      return
    }

    const serverName = servers[existingIndex].name
    const levelName = isProjectLevel ? 'Project-level' : 'User-level'
    
    // Check if server is already in the desired state
    if (servers[existingIndex].enabled === enabled) {
      this.interactor.displayText(`MCP server "${serverName}" is already ${enabled ? 'enabled' : 'disabled'}.`)
      return
    }

    // Update enabled status
    servers[existingIndex].enabled = enabled

    // Save updated configuration
    await this.saveServers(servers, isProjectLevel)
    
    const status = enabled ? 'enabled' : 'disabled'
    this.interactor.displayText(`${levelName} MCP server "${serverName}" is now ${status}`)
  }
  
  /**
   * Set the allowed tools for an MCP server
   * @param serverId ID of the server
   * @param tools Array of tool names to allow (empty array means all tools)
   * @param isProjectLevel Whether to modify project config instead of user config
   */
  async setServerAllowedTools(
    serverId: string, 
    tools: string[], 
    isProjectLevel: boolean = false
  ): Promise<void> {
    if (isProjectLevel && !this.projectService.selectedProject) {
      this.interactor.error('No project selected. Please select a project first.')
      return
    }
    
    const servers = this.getServers(isProjectLevel)
    
    // Find server with this ID
    const existingIndex = servers.findIndex((s) => s.id === serverId)
    if (existingIndex < 0) {
      this.interactor.error(`MCP server not found: ${serverId}`)
      return
    }

    const serverName = servers[existingIndex].name
    const levelName = isProjectLevel ? 'Project-level' : 'User-level'
    
    // Update allowed tools
    servers[existingIndex].allowedTools = tools.length > 0 ? tools : undefined
    
    // Save updated configuration
    await this.saveServers(servers, isProjectLevel)
    
    if (tools.length > 0) {
      this.interactor.displayText(
        `${levelName} MCP server "${serverName}" now allows these tools: ${tools.join(', ')}`
      )
    } else {
      this.interactor.displayText(
        `${levelName} MCP server "${serverName}" now allows all tools`
      )
    }
  }
  
  /**
   * Get servers from the selected level (user or project)
   */
  getServers(isProjectLevel: boolean = false): McpServerConfig[] {
    if (isProjectLevel) {
      const project = this.projectService.selectedProject
      if (!project) {
        return []
      }
      return project.config.mcp?.servers || []
    } else {
      return this.userService.config.mcp?.servers || []
    }
  }
  
  /**
   * Check if the current project has any MCP servers configured
   */
  hasProjectServers(): boolean {
    const project = this.projectService.selectedProject
    return !!(
      project && 
      project.config.mcp?.servers && 
      project.config.mcp.servers.length > 0
    )
  }
  
  /**
   * Save an individual server (add or update)
   */
  private async saveServer(serverConfig: McpServerConfig, isProjectLevel: boolean): Promise<void> {
    const servers = this.getServers(isProjectLevel)
    
    // Check if server with this ID already exists
    const existingIndex = servers.findIndex((s) => s.id === serverConfig.id)
    if (existingIndex >= 0) {
      servers[existingIndex] = serverConfig
    } else {
      servers.push(serverConfig)
    }
    
    await this.saveServers(servers, isProjectLevel)
  }
  
  /**
   * Save the full list of servers
   */
  private async saveServers(servers: McpServerConfig[], isProjectLevel: boolean): Promise<void> {
    if (isProjectLevel) {
      const project = this.projectService.selectedProject
      if (!project) {
        return
      }
      
      this.projectService.save({
        mcp: {
          servers
        }
      })
    } else {
      if (!this.userService.config.mcp) {
        this.userService.config.mcp = { servers: [] }
      }
      
      this.userService.config.mcp.servers = servers
      this.userService.save()
    }
  }
  
  /**
   * Get a list of server IDs for selection
   * @param isProjectLevel Whether to get project-level servers instead of user-level
   * @returns Array of server ID and name pairs
   */
  getServerSelectionOptions(isProjectLevel: boolean = false): Array<{id: string, name: string, enabled: boolean}> {
    const servers = this.getServers(isProjectLevel)
    return servers.map(server => ({
      id: server.id,
      name: server.name,
      enabled: server.enabled
    }))
  }
}