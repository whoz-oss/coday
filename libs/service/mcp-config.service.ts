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
   * Get servers from the selected level (user or project)
   */
  getServers(isProjectLevel: boolean = false): McpServerConfig[] {
    const project = this.projectService.selectedProject
    if (isProjectLevel) {
      if (!project) {
        return []
      }
      return project.config.mcp?.servers || []
    } else {
      const projects = this.userService.config.projects || {}
      return projects[project!.name]?.mcp?.servers || []
    }
  }

  getAllServers(): McpServerConfig[] {
    const projectConfigs = [...this.getServers(true)]
    const userOverrides = this.getServers(false)
    userOverrides.forEach((userOverride) => {
      const index = projectConfigs.findIndex((p) => p.name === userOverride.name && p.id === userOverride.id)
      if (index < 0) {
        projectConfigs.push(userOverride)
      } else {
        projectConfigs[index] = { ...projectConfigs[index], ...userOverride }
      }
    })
    return projectConfigs
  }

  /**
   * Save an individual server (add or update)
   */
  public async saveServer(serverConfig: McpServerConfig, isProjectLevel: boolean): Promise<void> {
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
    const project = this.projectService.selectedProject
    if (isProjectLevel) {
      if (!project) {
        return
      }

      // Create a shallow copy of the current config
      const updatedConfig = { ...project.config }

      // Update just the mcp.servers property
      updatedConfig.mcp = {
        ...updatedConfig.mcp, // Preserve other mcp settings if they exist
        servers,
      }

      // Save the whole config
      this.projectService.save(updatedConfig)
    } else {
      const projects = this.userService.config.projects || {}
      let userProjectConfig = projects[project!.name]
      if (!userProjectConfig) {
        this.userService.config.projects = {}
        this.userService.config.projects[project!.name] = { integration: {} }
        userProjectConfig = this.userService.config?.projects[project!.name]
      }

      // Update the MCP configuration while preserving other MCP settings
      userProjectConfig.mcp = {
        ...userProjectConfig.mcp, // Preserve other MCP settings if they exist
        servers,
      }

      this.userService.save()
    }
  }
}
