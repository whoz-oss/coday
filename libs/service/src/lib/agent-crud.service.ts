import * as path from 'node:path'
import * as os from 'node:os'
import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { readYamlFile, writeYamlFile } from '@coday/utils'
import type { AgentDefinition } from '@coday/model'
import { findFilesByName } from '@coday/function'
import type { ProjectService } from './project.service'

export type AgentLocation = 'project' | 'colocated'

export interface AgentSummaryWithMeta {
  name: string
  description: string
  source: AgentLocation
  editable: boolean
}

export interface AgentWithMeta {
  definition: AgentDefinition
  source: AgentLocation
  filePath: string
  editable: boolean
}

/**
 * AgentCrudService - Manages agent CRUD operations on YAML files
 *
 * Architecture:
 * - Agents can be stored in two editable locations:
 *   1. Project: ~/.coday/projects/{projectName}/agents/{name}.yml (personal config, not committed)
 *   2. Colocated: {projectPath}/agents/{name}.yml (next to coday.yaml, committable)
 * - Location is chosen at creation and is immutable
 * - Agent name is used as filename (must be unique within a project)
 * - Agent name validation: alphanumeric + hyphens/underscores, no spaces
 */
export class AgentCrudService {
  private readonly codayConfigDir: string
  private readonly projectService?: ProjectService

  constructor(codayConfigPath?: string, projectService?: ProjectService) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.codayConfigDir = codayConfigPath ?? defaultConfigPath
    this.projectService = projectService
  }

  /**
   * Resolve the filesystem path for a given project by name.
   */
  private getProjectPath(projectName: string): string | undefined {
    if (!this.projectService) return undefined
    return this.projectService.getProject(projectName)?.config.path
  }

  /**
   * Get agents directory path for a specific location.
   * Creates the directory if it doesn't exist.
   */
  private async getOrCreateAgentsDir(projectName: string, location: AgentLocation): Promise<string> {
    let agentsDir: string

    if (location === 'project') {
      agentsDir = path.join(this.codayConfigDir, 'projects', projectName, 'agents')
    } else {
      // colocated: find coday.yaml and put agents/ next to it
      const projectPath = this.getProjectPath(projectName)
      if (!projectPath) {
        throw new Error('Project path not configured, cannot access colocated agents')
      }

      const codayFiles = await findFilesByName({ text: 'coday.yaml', root: projectPath })
      if (codayFiles.length === 0) {
        throw new Error(`coday.yaml not found in project path: ${projectPath}`)
      }

      const codayFolder = path.dirname(codayFiles[0]!)
      agentsDir = path.join(projectPath, codayFolder, 'agents')
    }

    if (!existsSync(agentsDir)) {
      mkdirSync(agentsDir, { recursive: true })
      console.log(`[AGENT_CRUD] Created agents directory: ${agentsDir}`)
    }

    return agentsDir
  }

  /**
   * Find the actual file for an agent in a directory, case-insensitive and supporting both .yml/.yaml.
   * Returns the full file path if found, null otherwise.
   */
  private findAgentFile(agentsDir: string, agentName: string): string | null {
    if (!existsSync(agentsDir)) return null
    const lowerName = agentName.toLowerCase()
    const files = readdirSync(agentsDir).filter((f) => f.endsWith('.yml') || f.endsWith('.yaml'))
    const match = files.find((f) => f.replace(/\.ya?ml$/, '').toLowerCase() === lowerName)
    return match ? path.join(agentsDir, match) : null
  }

  /**
   * Find which location contains an agent by name (case-insensitive, .yml/.yaml).
   * Returns location and the resolved file path, or null.
   */
  private async findAgentFile2(
    projectName: string,
    agentName: string
  ): Promise<{ location: AgentLocation; filePath: string } | null> {
    // Check project location
    const projectAgentsDir = path.join(this.codayConfigDir, 'projects', projectName, 'agents')
    const projectFile = this.findAgentFile(projectAgentsDir, agentName)
    if (projectFile) return { location: 'project', filePath: projectFile }

    // Check colocated
    if (this.projectService) {
      try {
        const colocatedDir = await this.getOrCreateAgentsDir(projectName, 'colocated')
        const colocatedFile = this.findAgentFile(colocatedDir, agentName)
        if (colocatedFile) return { location: 'colocated', filePath: colocatedFile }
      } catch {
        // coday.yaml not found or project path not configured — normal case
      }
    }

    return null
  }

  /**
   * Validate agent name format: alphanumeric + hyphens/underscores, no spaces.
   */
  private validateAgentName(name: string): void {
    if (!name || typeof name !== 'string') {
      throw new Error('Agent name is required')
    }
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name)) {
      throw new Error(
        'Agent name must start with alphanumeric and contain only letters, digits, hyphens or underscores'
      )
    }
  }

  /**
   * List all editable agents for a project (both locations).
   */
  async list(projectName: string): Promise<AgentSummaryWithMeta[]> {
    const agents: AgentSummaryWithMeta[] = []
    const locations: AgentLocation[] = ['project']

    if (this.projectService) {
      locations.push('colocated')
    }

    for (const location of locations) {
      try {
        const agentsDir = await this.getOrCreateAgentsDir(projectName, location)

        if (!existsSync(agentsDir)) continue

        const files = readdirSync(agentsDir).filter((f) => f.endsWith('.yml') || f.endsWith('.yaml'))

        for (const file of files) {
          const agentName = file.replace(/\.ya?ml$/, '')
          const filePath = path.join(agentsDir, file)
          const definition = readYamlFile<AgentDefinition>(filePath)
          if (definition) {
            agents.push({
              name: definition.name ?? agentName,
              description: definition.description ?? '',
              source: location,
              editable: true,
            })
          }
        }
      } catch (error) {
        console.log(`[AGENT_CRUD] Could not access ${location} agents for ${projectName}:`, error)
      }
    }

    return agents.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
  }

  /**
   * Get a single agent definition with its metadata.
   */
  async get(projectName: string, agentName: string): Promise<AgentWithMeta | null> {
    const found = await this.findAgentFile2(projectName, agentName)
    if (!found) return null

    const definition = readYamlFile<AgentDefinition>(found.filePath)
    if (!definition) return null

    return { definition, source: found.location, filePath: found.filePath, editable: true }
  }

  /**
   * Create a new agent YAML file in the specified location.
   */
  async create(projectName: string, definition: AgentDefinition, location: AgentLocation): Promise<AgentWithMeta> {
    this.validateAgentName(definition.name)

    if (!definition.description) {
      throw new Error('Agent description is required')
    }

    // Check for name conflict across both locations (case-insensitive)
    const existing = await this.findAgentFile2(projectName, definition.name)
    if (existing) {
      throw new Error(`Agent '${definition.name}' already exists in ${existing.location} location`)
    }

    const agentsDir = await this.getOrCreateAgentsDir(projectName, location)
    const filePath = path.join(agentsDir, `${definition.name}.yml`)
    writeYamlFile(filePath, definition)
    console.log(`[AGENT_CRUD] Created agent '${definition.name}' in ${location} for project ${projectName}`)

    return { definition, source: location, filePath, editable: true }
  }

  /**
   * Update an existing agent YAML file.
   * Location is immutable after creation.
   */
  async update(projectName: string, agentName: string, definition: AgentDefinition): Promise<AgentWithMeta | null> {
    const found = await this.findAgentFile2(projectName, agentName)
    if (!found) return null

    if (definition.description !== undefined && !definition.description) {
      throw new Error('Agent description cannot be empty')
    }

    const existing = readYamlFile<AgentDefinition>(found.filePath)
    if (!existing) return null

    // Merge: start from definition (which carries explicit undefined for cleared fields),
    // then restore the original name. We avoid spreading existing first so that
    // undefined values in definition properly clear optional fields.
    const updated: AgentDefinition = {
      name: existing.name,
      description: definition.description ?? existing.description,
      instructions: definition.instructions,
      aiProvider: definition.aiProvider,
      modelName: definition.modelName,
      temperature: definition.temperature,
      maxOutputTokens: definition.maxOutputTokens,
      openaiAssistantId: definition.openaiAssistantId,
      integrations: definition.integrations,
      mandatoryDocs: definition.mandatoryDocs ?? existing.mandatoryDocs,
      optionalDocs: definition.optionalDocs ?? existing.optionalDocs,
    }
    // Remove undefined keys so the YAML stays clean
    Object.keys(updated).forEach((k) => {
      if ((updated as any)[k] === undefined) delete (updated as any)[k]
    })
    writeYamlFile(found.filePath, updated)
    console.log(`[AGENT_CRUD] Updated agent '${agentName}' in ${found.location} for project ${projectName}`)

    return { definition: updated, source: found.location, filePath: found.filePath, editable: true }
  }

  /**
   * Delete an agent YAML file.
   */
  async delete(projectName: string, agentName: string): Promise<boolean> {
    const found = await this.findAgentFile2(projectName, agentName)
    if (!found) return false

    unlinkSync(found.filePath)
    console.log(`[AGENT_CRUD] Deleted agent '${agentName}' from ${found.location} for project ${projectName}`)
    return true
  }
}
