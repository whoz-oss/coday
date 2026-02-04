import * as path from 'node:path'
import * as os from 'node:os'
import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { readYamlFile, writeYamlFile } from '@coday/utils'
import type { Prompt, PromptInfo } from '@coday/model'
import { isUserAdmin } from './user-groups'

/**
 * PromptService - Manages prompt CRUD operations
 *
 * Architecture:
 * - Prompts are stored per project: ~/.coday/projects/{projectName}/prompts/{id}.yml
 * - Each prompt is owned by a user (createdBy field)
 * - Access control:
 *   - Anyone can view/edit prompts (collaborative)
 *   - Only CODAY_ADMIN can toggle webhookEnabled flag
 */
export class PromptService {
  private readonly codayConfigDir: string

  constructor(codayConfigPath?: string) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.codayConfigDir = codayConfigPath ?? defaultConfigPath
  }

  /**
   * Get prompts directory path for a specific project
   */
  private getPromptsDir(projectName: string): string {
    return path.join(this.codayConfigDir, 'projects', projectName, 'prompts')
  }

  /**
   * Get prompt file path
   */
  private getPromptFilePath(projectName: string, id: string): string {
    return path.join(this.getPromptsDir(projectName), `${id}.yml`)
  }

  /**
   * Find which project contains a prompt by ID
   * Used by execution to locate prompt without knowing project
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
      const promptPath = this.getPromptFilePath(projectName, id)
      if (existsSync(promptPath)) {
        return projectName
      }
    }

    return null
  }

  /**
   * Creates a new prompt with generated ID and timestamp
   *
   * @param projectName - Project name where prompt will be created
   * @param prompt - Prompt data (without id and createdAt)
   * @returns Created prompt
   */
  async create(projectName: string, prompt: Omit<Prompt, 'id' | 'createdAt'>): Promise<Prompt> {
    try {
      // Generate proper UUID v4
      const id = randomUUID()

      const newPrompt: Prompt = {
        ...prompt,
        id,
        createdAt: new Date().toISOString(),
      }

      const promptsDir = this.getPromptsDir(projectName)
      mkdirSync(promptsDir, { recursive: true })

      const filePath = this.getPromptFilePath(projectName, id)

      // Check if file already exists (highly unlikely but defensive)
      if (existsSync(filePath)) {
        throw new Error(`Prompt with ID ${id} already exists`)
      }

      writeYamlFile(filePath, newPrompt)
      console.log(`[PROMPT] Created prompt ${id} in project ${projectName}`)
      return newPrompt
    } catch (error) {
      throw new Error(`Failed to create prompt: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }

  /**
   * Retrieves a prompt by ID and project
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @returns Prompt if found, null otherwise
   */
  async get(projectName: string, id: string): Promise<Prompt | null> {
    try {
      const filePath = this.getPromptFilePath(projectName, id)
      const prompt = readYamlFile<Prompt>(filePath)

      if (!prompt) {
        return null
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
   *
   * Special rule: Only CODAY_ADMIN can modify webhookEnabled flag
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @param updates - Fields to update
   * @param username - Username requesting the update
   * @returns Updated prompt if successful, null if not found
   * @throws Error if non-admin tries to modify webhookEnabled
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

      // Prevent changing ID and createdAt
      const { id: _, createdAt: __, ...allowedUpdates } = updates

      const updatedPrompt: Prompt = {
        ...existing,
        ...allowedUpdates,
        updatedAt: new Date().toISOString(),
      }

      const filePath = this.getPromptFilePath(projectName, id)
      writeYamlFile(filePath, updatedPrompt)

      console.log(`[PROMPT] Updated prompt ${id} by user ${username}`)
      return updatedPrompt
    } catch (error) {
      console.error(`Failed to update prompt ${id}:`, error)
      throw error
    }
  }

  /**
   * Deletes a prompt by ID
   *
   * @param projectName - Project name
   * @param id - Prompt ID
   * @returns true if deleted, false if not found
   */
  async delete(projectName: string, id: string): Promise<boolean> {
    try {
      const existing = await this.get(projectName, id)
      if (!existing) {
        return false
      }

      const filePath = this.getPromptFilePath(projectName, id)
      unlinkSync(filePath)

      console.log(`[PROMPT] Deleted prompt ${id}`)
      return true
    } catch (error) {
      console.error(`Failed to delete prompt ${id}:`, error)
      return false
    }
  }

  /**
   * Lists all prompts for a project
   *
   * @param projectName - Project name
   * @returns Array of prompts
   */
  async list(projectName: string): Promise<PromptInfo[]> {
    try {
      const promptsDir = this.getPromptsDir(projectName)

      if (!existsSync(promptsDir)) {
        return []
      }

      const files = readdirSync(promptsDir)
      const promptFiles = files.filter((file) => file.endsWith('.yml'))

      const prompts: PromptInfo[] = []

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
          })
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
