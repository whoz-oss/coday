import express from 'express'
import { debugLog } from './log'
import { PromptService, Prompt } from '@coday/service'
import { validateInterval } from '@coday/utils'
import { getParamAsString } from './route-helpers'

/**
 * Prompt Management REST API Routes
 *
 * Architecture:
 * - CRUD operations are scoped to projects: /api/projects/:projectName/prompts
 * - All users can view and edit prompts (collaborative)
 * - Only CODAY_ADMIN can toggle webhookEnabled flag
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/prompts               - List prompts for project
 * - GET    /api/projects/:projectName/prompts/:id           - Get specific prompt
 * - POST   /api/projects/:projectName/prompts               - Create new prompt
 * - PUT    /api/projects/:projectName/prompts/:id           - Update prompt
 * - DELETE /api/projects/:projectName/prompts/:id           - Delete prompt
 * - POST   /api/projects/:projectName/prompts/:id/webhook   - Enable webhook (CODAY_ADMIN)
 * - DELETE /api/projects/:projectName/prompts/:id/webhook   - Disable webhook (CODAY_ADMIN)
 */

/**
 * Register prompt management routes on the Express app
 * @param app - Express application instance
 * @param promptService - Prompt service instance for CRUD operations
 * @param getUsernameFn - Function to extract username from request
 */
export function registerPromptRoutes(
  app: express.Application,
  promptService: PromptService,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/projects/:projectName/prompts
   * List all prompts for a project (no access control - all users can see)
   */
  app.get('/api/projects/:projectName/prompts', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      debugLog('PROMPT_API', `GET prompts for project: ${projectName}`)
      const prompts = await promptService.list(projectName)
      res.status(200).json(prompts)
    } catch (error) {
      console.error('Error listing prompts:', error)
      res.status(500).json({ error: 'Failed to list prompts' })
    }
  })

  /**
   * GET /api/projects/:projectName/prompts/:id
   * Get specific prompt by ID
   */
  app.get('/api/projects/:projectName/prompts/:id', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Prompt ID is required' })
        return
      }

      debugLog('PROMPT_API', `GET prompt: ${id} in project: ${projectName}`)
      const prompt = await promptService.get(projectName, id)

      if (!prompt) {
        res.status(404).json({ error: `Prompt with ID '${id}' not found` })
        return
      }

      res.status(200).json(prompt)
    } catch (error) {
      console.error('Error retrieving prompt:', error)
      res.status(500).json({ error: 'Failed to retrieve prompt' })
    }
  })

  /**
   * POST /api/projects/:projectName/prompts
   * Create new prompt in project
   */
  app.post('/api/projects/:projectName/prompts', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const promptData = req.body as Omit<Prompt, 'id' | 'createdAt' | 'createdBy' | 'updatedAt'>

      // Basic validation
      if (!promptData || typeof promptData !== 'object') {
        res.status(422).json({ error: 'Invalid prompt format' })
        return
      }

      if (!promptData.name || typeof promptData.name !== 'string') {
        res.status(422).json({ error: 'Prompt name is required' })
        return
      }

      // Validate name format (lowercase, hyphens, alphanumeric)
      if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(promptData.name)) {
        res.status(422).json({
          error: 'Prompt name must be lowercase alphanumeric with hyphens (e.g., my-prompt-name)',
        })
        return
      }

      if (!promptData.description || typeof promptData.description !== 'string') {
        res.status(422).json({ error: 'Prompt description is required' })
        return
      }

      if (!promptData.commands || !Array.isArray(promptData.commands) || promptData.commands.length === 0) {
        res.status(422).json({ error: 'Prompt must have at least one command' })
        return
      }

      // Validate webhookEnabled is boolean
      if (promptData.webhookEnabled !== undefined && typeof promptData.webhookEnabled !== 'boolean') {
        res.status(422).json({ error: 'webhookEnabled must be a boolean' })
        return
      }

      // Validate threadLifetime format if provided
      if (promptData.threadLifetime !== undefined) {
        if (typeof promptData.threadLifetime !== 'string') {
          res.status(422).json({ error: 'threadLifetime must be a string' })
          return
        }
        if (!validateInterval(promptData.threadLifetime)) {
          res.status(422).json({
            error: "Invalid threadLifetime format. Use format like '2min', '5h', '14d', '1M'",
          })
          return
        }
      }

      // Prevent manual setting of activeThreadId (managed automatically)
      if (promptData.activeThreadId !== undefined) {
        res.status(422).json({ error: 'activeThreadId is managed automatically and cannot be set manually' })
        return
      }

      // Get username for createdBy field
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('PROMPT_API', `POST new prompt: ${promptData.name} in project: ${projectName}`)

      const newPrompt = await promptService.create(projectName, {
        ...promptData,
        webhookEnabled: promptData.webhookEnabled ?? false, // Default to false
        createdBy: username,
      })

      res.status(201).json(newPrompt)
    } catch (error) {
      console.error('Error creating prompt:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to create prompt: ${errorMessage}` })
    }
  })

  /**
   * PUT /api/projects/:projectName/prompts/:id
   * Update existing prompt
   * Note: Only CODAY_ADMIN can modify webhookEnabled flag (enforced by service)
   */
  app.put('/api/projects/:projectName/prompts/:id', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(404).json({ error: 'Prompt ID is required' })
        return
      }

      const updates = req.body as Partial<Prompt>

      // Basic validation
      if (!updates || typeof updates !== 'object') {
        res.status(422).json({ error: 'Invalid prompt format' })
        return
      }

      // Validate name format if provided
      if (updates.name !== undefined) {
        if (typeof updates.name !== 'string') {
          res.status(422).json({ error: 'Prompt name must be a string' })
          return
        }
        if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(updates.name)) {
          res.status(422).json({
            error: 'Prompt name must be lowercase alphanumeric with hyphens (e.g., my-prompt-name)',
          })
          return
        }
      }

      // Validate commands if provided
      if (updates.commands !== undefined) {
        if (!Array.isArray(updates.commands) || updates.commands.length === 0) {
          res.status(422).json({ error: 'Prompt must have at least one command' })
          return
        }
      }

      // Validate webhookEnabled if provided
      if (updates.webhookEnabled !== undefined && typeof updates.webhookEnabled !== 'boolean') {
        res.status(422).json({ error: 'webhookEnabled must be a boolean' })
        return
      }

      // Validate threadLifetime format if provided
      if (updates.threadLifetime !== undefined) {
        if (updates.threadLifetime !== null && typeof updates.threadLifetime !== 'string') {
          res.status(422).json({ error: 'threadLifetime must be a string or null' })
          return
        }
        if (updates.threadLifetime && !validateInterval(updates.threadLifetime)) {
          res.status(422).json({
            error: "Invalid threadLifetime format. Use format like '2min', '5h', '14d', '1M'",
          })
          return
        }
      }

      // Prevent manual modification of activeThreadId (managed automatically)
      if (updates.activeThreadId !== undefined) {
        res.status(422).json({ error: 'activeThreadId is managed automatically and cannot be modified manually' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('PROMPT_API', `PUT prompt: ${id} in project: ${projectName}, user: ${username}`)

      const updatedPrompt = await promptService.update(projectName, id, updates, username)

      if (!updatedPrompt) {
        res.status(404).json({ error: `Prompt with ID '${id}' not found` })
        return
      }

      res.status(200).json({ success: true, prompt: updatedPrompt })
    } catch (error) {
      console.error('Error updating prompt:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'

      // Check for CODAY_ADMIN permission error
      if (errorMessage.includes('Only CODAY_ADMIN')) {
        res.status(403).json({ error: errorMessage })
        return
      }

      res.status(500).json({ error: `Failed to update prompt: ${errorMessage}` })
    }
  })

  /**
   * DELETE /api/projects/:projectName/prompts/:id
   * Delete prompt
   */
  app.delete('/api/projects/:projectName/prompts/:id', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Prompt ID is required' })
        return
      }

      debugLog('PROMPT_API', `DELETE prompt: ${id} in project: ${projectName}`)

      const success = await promptService.delete(projectName, id)

      if (!success) {
        res.status(404).json({ error: `Prompt with ID '${id}' not found` })
        return
      }

      res.status(200).json({ success: true, message: 'Prompt deleted successfully' })
    } catch (error) {
      console.error('Error deleting prompt:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to delete prompt: ${errorMessage}` })
    }
  })

  /**
   * POST /api/projects/:projectName/prompts/:id/webhook
   * Enable webhook for a prompt (CODAY_ADMIN only)
   */
  app.post('/api/projects/:projectName/prompts/:id/webhook', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Prompt ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('PROMPT_API', `POST enable webhook for prompt: ${id} in project: ${projectName}, user: ${username}`)

      const updatedPrompt = await promptService.enableWebhook(projectName, id, username)

      if (!updatedPrompt) {
        res.status(404).json({ error: `Prompt with ID '${id}' not found` })
        return
      }

      res.status(200).json({ success: true, prompt: updatedPrompt })
    } catch (error) {
      console.error('Error enabling webhook:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'

      // Check for CODAY_ADMIN permission error
      if (errorMessage.includes('Only CODAY_ADMIN')) {
        res.status(403).json({ error: errorMessage })
        return
      }

      res.status(500).json({ error: `Failed to enable webhook: ${errorMessage}` })
    }
  })

  /**
   * DELETE /api/projects/:projectName/prompts/:id/webhook
   * Disable webhook for a prompt (CODAY_ADMIN only)
   */
  app.delete('/api/projects/:projectName/prompts/:id/webhook', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const id = getParamAsString(req.params.id)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!id) {
        res.status(400).json({ error: 'Prompt ID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('PROMPT_API', `DELETE disable webhook for prompt: ${id} in project: ${projectName}, user: ${username}`)

      const updatedPrompt = await promptService.disableWebhook(projectName, id, username)

      if (!updatedPrompt) {
        res.status(404).json({ error: `Prompt with ID '${id}' not found` })
        return
      }

      res.status(200).json({ success: true, prompt: updatedPrompt })
    } catch (error) {
      console.error('Error disabling webhook:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'

      // Check for CODAY_ADMIN permission error
      if (errorMessage.includes('Only CODAY_ADMIN')) {
        res.status(403).json({ error: errorMessage })
        return
      }

      res.status(500).json({ error: `Failed to disable webhook: ${errorMessage}` })
    }
  })
}
