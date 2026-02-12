import * as path from 'node:path'
import * as os from 'node:os'
import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { readYamlFile, writeYamlFile } from '@coday/utils'
import type { Prompt, PromptInfo, PromptSource } from '@coday/model'
import { findFilesByName } from '@coday/function'
import { isUserAdmin } from './user-groups'

/**
 * PromptService - Manages prompt CRUD operations
 *
 * Architecture:
 * - Prompts can be stored in two locations:
 *   1. Local: ~/.coday/projects/{projectName}/prompts/{id}.yml (personal, not committed)
 *   2. Project: {projectPath}/prompts/{id}.yml (next to coday.yaml, committable)
 * - Source is chosen at creation and is immutable
 * - Each prompt is owned by a user (createdBy field)
 * - Access control:
 *   - Anyone can view/edit prompts (collaborative)
 *   - Only CODAY_ADMIN can toggle webhookEnabled flag
 */
export class PromptService {
  private readonly codayConfigDir: string
  private projectPath?: string

  constructor(codayConfigPath?: string, projectPath?: string) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.codayConfigDir = codayConfigPath ?? defaultConfigPath
    this.projectPath = projectPath
  }

  /**
   * Get prompts directory path for a specific source
   */
  private async getPromptsDir(projectName: string, source: PromptSource): Promise<string> {
    if (source === 'local') {
      return path.join(this.codayConfigDir, 'projects', projectName, 'prompts')
    } else {
      // project source: find coday.yaml and put prompts/ next to it
      if (!this.projectPath) {
        throw new Error('Project path not configured, cannot access project prompts')
      }

      // Find coday.yaml location (same logic as agent.service.ts)
      const codayFiles = await findFilesByName({ text: 'coday.yaml', root: this.projectPath })
      if (codayFiles.length === 0) {
        throw new Error(`coday.yaml not found in project path: ${this.projectPath}`)
      }

      const codayFolder = path.dirname(codayFiles[0]!)
      return path.join(this.projectPath, codayFolder, 'prompts')
    }
  }

  /**
   * Get prompt file path
   */
  private async getPromptFilePath(projectName: string, id: string, source: PromptSource): Promise<string> {
    const dir = await this.getPromptsDir(projectName, source)
    return path.join(dir, `${id}.yml`)
  }

  /**
   * Find which source contains a prompt by ID
   * Checks both local and project sources
   */
  private async findPromptSource(projectName: string, id: string): Promise<PromptSource | null> {
    // Check local first (most common)
    const localPath = await this.getPromptFilePath(projectName, id, 'local')
    if (existsSync(localPath)) {
      return 'local'
    }

    // Check project
    if (this.projectPath) {
      try {
        const projectPath = await this.getPromptFilePath(projectName, id, 'project')
        if (existsSync(projectPath)) {
          return 'project'
        }
      } catch (error) {
        // Project prompts directory doesn't exist or coday.yaml not found
        // This is normal, not all projects have project prompts
      }
    }

    return null
  }

  /**
   * Find which project contains a prompt by ID
   * Used by execution to locate prompt without knowing project
   * Searches in local prompts only for now (webhooks typically use local prompts)
   *
   * @param id - Prompt ID
   * @returns Project name if found, null otherwise
   */
  findProjectForPrompt(id: string): string | null {
    const projectsPath = path.join(this.codayConfigDir, 'projects')

    if (!existsSync(projectsPath)) {
      return null
    }

    const projectDirs = readdirSync(projectsPath, { withFileTypes: true })
      .filter((dirent) => dirent.isDirectory())
      .map((dirent) => dirent.name)

    for (const projectName of projectDirs) {
      // Check local prompts
      const localPath = path.join(this.codayConfigDir, 'projects', projectName, 'prompts', `${id}.yml`)
      if (existsSync(localPath)) {
        return projectName
      }

      // TODO: Also check project prompts if needed for webhook execution
    }

    return null
  }

  /**
   * Creates a new prompt with generated ID and timestamp
   *
   * @param projectName - Project name where prompt will be created
   * @param prompt - Prompt data (without id and createdAt, but with source)
   * @param source - Storage location ('local' or 'project'), defaults to 'local'
   * @returns Created prompt
   */
  async create(
    projectName: string,
    prompt: Omit<Prompt, 'id' | 'createdAt'>,
    source: PromptSource = 'local'
  ): Promise<Prompt> {
    try {
      // Generate proper UUID v4
      const id = randomUUID()

      const newPrompt: Prompt = {
        ...prompt,
        id,
        source, // Store source in the prompt
        createdAt: new Date().toISOString(),
      }

      const promptsDir = await this.getPromptsDir(projectName, source)
      mkdirSync(promptsDir, { recursive: true })

      const filePath = await this.getPromptFilePath(projectName, id, source)

      // Check if file already exists (highly unlikely but defensive)
      if (existsSync(filePath)) {
        throw new Error(`Prompt with ID ${id} already exists`)
      }

      writeYamlFile(filePath, newPrompt)
      console.log(`[PROMPT] Created prompt ${id} in ${source} for project ${projectName}`)
      return newPrompt
    } catch (error) {
      throw new Error(`Failed to create prompt: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }

  /**
   * Retrieves a prompt by ID and project
   * Automatically finds the source (local or project)
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @returns Prompt if found, null otherwise
   */
  async get(projectName: string, id: string): Promise<Prompt | null> {
    try {
      const source = await this.findPromptSource(projectName, id)
      if (!source) {
        return null
      }

      const filePath = await this.getPromptFilePath(projectName, id, source)
      const prompt = readYamlFile<Prompt>(filePath)

      if (!prompt) {
        return null
      }

      // Ensure source is set (for backwards compatibility with prompts created before source field)
      if (!prompt.source) {
        prompt.source = source
      }

      return prompt
    } catch (error) {
      console.error(`Failed to get prompt ${id}:`, error)
      return null
    }
  }

  /**
   * Get prompt by ID without knowing project (for execution)
   *
   * @param id - Prompt ID
   * @returns Object with prompt and projectName if found, null otherwise
   */
  async getById(id: string): Promise<{ prompt: Prompt; projectName: string } | null> {
    try {
      const projectName = this.findProjectForPrompt(id)
      if (!projectName) {
        return null
      }

      const prompt = await this.get(projectName, id)
      if (!prompt) {
        return null
      }

      return { prompt, projectName }
    } catch (error) {
      console.error(`Failed to get prompt ${id}:`, error)
      return null
    }
  }

  /**
   * Updates an existing prompt
   * Updates the file where it currently exists (source is immutable after creation)
   *
   * Special rules:
   * - Only CODAY_ADMIN can modify webhookEnabled flag
   * - Source cannot be changed after creation
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @param updates - Fields to update
   * @param username - Username requesting the update
   * @returns Updated prompt if successful, null if not found
   * @throws Error if non-admin tries to modify webhookEnabled or if source change attempted
   */
  async update(projectName: string, id: string, updates: Partial<Prompt>, username: string): Promise<Prompt | null> {
    try {
      const existing = await this.get(projectName, id)
      if (!existing) {
        return null
      }

      // Check if webhookEnabled is being modified
      if (updates.webhookEnabled !== undefined && updates.webhookEnabled !== existing.webhookEnabled) {
        // Only CODAY_ADMIN can modify webhookEnabled
        if (!isUserAdmin(username, this.codayConfigDir)) {
          throw new Error('Only CODAY_ADMIN can enable/disable webhook for prompts')
        }
      }

      // Prevent changing ID, createdAt, and source
      const { id: _, createdAt: __, source: ___, ...allowedUpdates } = updates

      const updatedPrompt: Prompt = {
        ...existing,
        ...allowedUpdates,
        updatedAt: new Date().toISOString(),
      }

      // Write to the original source (immutable)
      const filePath = await this.getPromptFilePath(projectName, id, existing.source)
      writeYamlFile(filePath, updatedPrompt)

      console.log(`[PROMPT] Updated prompt ${id} (${existing.source}) by user ${username}`)
      return updatedPrompt
    } catch (error) {
      console.error(`Failed to update prompt ${id}:`, error)
      throw error
    }
  }

  /**
   * Deletes a prompt by ID
   * Deletes from whichever source it's in
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @returns true if deleted, false if not found
   */
  async delete(projectName: string, id: string): Promise<boolean> {
    try {
      const source = await this.findPromptSource(projectName, id)
      if (!source) {
        return false
      }

      const filePath = await this.getPromptFilePath(projectName, id, source)
      unlinkSync(filePath)

      console.log(`[PROMPT] Deleted prompt ${id} from ${source}`)
      return true
    } catch (error) {
      console.error(`Failed to delete prompt ${id}:`, error)
      return false
    }
  }

  /**
   * Lists all prompts for a project from both sources
   * Returns prompts with their source indicated
   *
   * @param projectName - Project name
   * @returns Array of prompts from both local and project sources
   */
  async list(projectName: string): Promise<PromptInfo[]> {
    try {
      const prompts: PromptInfo[] = []
      const sources: PromptSource[] = ['local']

      if (this.projectPath) {
        sources.push('project')
      }

      for (const source of sources) {
        try {
          const promptsDir = await this.getPromptsDir(projectName, source)

          if (!existsSync(promptsDir)) {
            continue
          }

          const files = readdirSync(promptsDir)
          const promptFiles = files.filter((file) => file.endsWith('.yml'))

          for (const file of promptFiles) {
            const id = file.replace('.yml', '')
            const prompt = await this.get(projectName, id)
            if (prompt) {
              prompts.push({
                id: prompt.id,
                name: prompt.name,
                description: prompt.description,
                webhookEnabled: prompt.webhookEnabled,
                createdBy: prompt.createdBy,
                createdAt: prompt.createdAt,
                updatedAt: prompt.updatedAt,
                source: prompt.source || source, // Fallback for backwards compat
              })
            }
          }
        } catch (error) {
          // Error accessing this source (e.g., coday.yaml not found for project source)
          // This is normal, not all projects have both sources
          console.log(`[PROMPT] Could not access ${source} prompts for ${projectName}:`, error)
        }
      }

      // Sort by creation date (newest first)
      return prompts.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    } catch (error) {
      console.error(`Failed to list prompts for project ${projectName}:`, error)
      return []
    }
  }

  /**
   * Enable webhook for a prompt (CODAY_ADMIN only)
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @param username - Username requesting the change
   * @returns Updated prompt
   * @throws Error if user is not admin
   */
  async enableWebhook(projectName: string, id: string, username: string): Promise<Prompt | null> {
    return this.update(projectName, id, { webhookEnabled: true }, username)
  }

  /**
   * Disable webhook for a prompt (CODAY_ADMIN only)
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @param username - Username requesting the change
   * @returns Updated prompt
   * @throws Error if user is not admin
   */
  async disableWebhook(projectName: string, id: string, username: string): Promise<Prompt | null> {
    return this.update(projectName, id, { webhookEnabled: false }, username)
  }
}
