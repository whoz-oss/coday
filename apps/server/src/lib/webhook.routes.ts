import express from 'express'
import { debugLog } from './log'
import { WebhookService, Webhook } from '@coday/service'
import { getParamAsString } from './route-helpers'

/**
 * Webhook Management REST API Routes
 *
 * NEW ARCHITECTURE:
 * - CRUD operations are scoped to projects: /api/projects/:projectName/webhooks
 * - Execution endpoint remains project-agnostic: /api/webhooks/:uuid/execute
 * - Access control: owner OR CODAY_ADMIN group members can access
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/webhooks          - List webhooks for project
 * - GET    /api/projects/:projectName/webhooks/:uuid    - Get specific webhook
 * - POST   /api/projects/:projectName/webhooks          - Create new webhook
 * - PUT    /api/projects/:projectName/webhooks/:uuid    - Update webhook
 * - DELETE /api/projects/:projectName/webhooks/:uuid    - Delete webhook
 * - POST   /api/webhooks/:uuid/execute                   - Execute webhook (project-agnostic)
 */

/**
 * Register webhook management routes on the Express app
 * @param app - Express application instance
 * @param webhookService - Webhook service instance for CRUD and execution operations
 * @param getUsernameFn - Function to extract username from request (for createdBy field)
 */
export function registerWebhookRoutes(
  app: express.Application,
  webhookService: WebhookService,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/projects/:projectName/webhooks
   * List all webhooks for a project (filtered by access control)
   */
  app.get('/api/projects/:projectName/webhooks', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('WEBHOOK_API', `GET webhooks for project: ${projectName}, user: ${username}`)
      const webhooks = await webhookService.list(projectName, username)
      res.status(200).json(webhooks)
    } catch (error) {
      console.error('Error listing webhooks:', error)
      res.status(500).json({ error: 'Failed to list webhooks' })
    }
  })

  /**
   * GET /api/projects/:projectName/webhooks/:uuid
   * Get specific webhook by UUID (with access control)
   */
  app.get('/api/projects/:projectName/webhooks/:uuid', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const uuid = getParamAsString(req.params.uuid)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!uuid) {
        res.status(400).json({ error: 'Webhook UUID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('WEBHOOK_API', `GET webhook: ${uuid} in project: ${projectName}, user: ${username}`)
      const webhook = await webhookService.get(projectName, uuid, username)

      if (!webhook) {
        res.status(404).json({ error: `Webhook with UUID '${uuid}' not found or access denied` })
        return
      }

      res.status(200).json(webhook)
    } catch (error) {
      console.error('Error retrieving webhook:', error)
      res.status(500).json({ error: 'Failed to retrieve webhook' })
    }
  })

  /**
   * POST /api/projects/:projectName/webhooks
   * Create new webhook in project
   */
  app.post('/api/projects/:projectName/webhooks', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      const webhookData = req.body as Omit<Webhook, 'uuid' | 'createdAt' | 'createdBy'>

      // Basic validation
      if (!webhookData || typeof webhookData !== 'object') {
        res.status(422).json({ error: 'Invalid webhook format' })
        return
      }

      if (!webhookData.name || typeof webhookData.name !== 'string') {
        res.status(422).json({ error: 'Webhook name is required' })
        return
      }

      if (!webhookData.commandType || !['free', 'template'].includes(webhookData.commandType)) {
        res.status(422).json({ error: 'Webhook commandType must be either "free" or "template"' })
        return
      }

      // For template type, commands are required
      if (webhookData.commandType === 'template') {
        if (!webhookData.commands || !Array.isArray(webhookData.commands) || webhookData.commands.length === 0) {
          res.status(422).json({ error: 'Template webhooks must have at least one command' })
          return
        }
      }

      // Get username for createdBy field
      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('WEBHOOK_API', `POST new webhook: ${webhookData.name} in project: ${projectName}`)

      const newWebhook = await webhookService.create(projectName, {
        ...webhookData,
        createdBy: username,
      })

      res.status(201).json(newWebhook)
    } catch (error) {
      console.error('Error creating webhook:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to create webhook: ${errorMessage}` })
    }
  })

  /**
   * PUT /api/projects/:projectName/webhooks/:uuid
   * Update existing webhook (with access control)
   */
  app.put('/api/projects/:projectName/webhooks/:uuid', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const uuid = getParamAsString(req.params.uuid)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!uuid) {
        res.status(404).json({ error: 'Webhook UUID is required' })
        return
      }

      const updates = req.body as Partial<Webhook>

      // Basic validation
      if (!updates || typeof updates !== 'object') {
        res.status(422).json({ error: 'Invalid webhook format' })
        return
      }

      // Validate commandType if provided
      if (updates.commandType && !['free', 'template'].includes(updates.commandType)) {
        res.status(422).json({ error: 'Webhook commandType must be either "free" or "template"' })
        return
      }

      // Validate commands for template type if commandType is being updated
      if (updates.commandType === 'template') {
        if (!updates.commands || !Array.isArray(updates.commands) || updates.commands.length === 0) {
          res.status(422).json({ error: 'Template webhooks must have at least one command' })
          return
        }
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('WEBHOOK_API', `PUT webhook: ${uuid} in project: ${projectName}, user: ${username}`)

      const updatedWebhook = await webhookService.update(projectName, uuid, updates, username)

      if (!updatedWebhook) {
        res.status(404).json({ error: `Webhook with UUID '${uuid}' not found or access denied` })
        return
      }

      res.status(200).json({ success: true, webhook: updatedWebhook })
    } catch (error) {
      console.error('Error updating webhook:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to update webhook: ${errorMessage}` })
    }
  })

  /**
   * DELETE /api/projects/:projectName/webhooks/:uuid
   * Delete webhook (with access control)
   */
  app.delete('/api/projects/:projectName/webhooks/:uuid', async (req: express.Request, res: express.Response) => {
    try {
      const projectName = getParamAsString(req.params.projectName)
      const uuid = getParamAsString(req.params.uuid)

      if (!projectName) {
        res.status(400).json({ error: 'Project name is required' })
        return
      }

      if (!uuid) {
        res.status(400).json({ error: 'Webhook UUID is required' })
        return
      }

      const username = getUsernameFn(req)
      if (!username) {
        res.status(401).json({ error: 'Username not found in request headers' })
        return
      }

      debugLog('WEBHOOK_API', `DELETE webhook: ${uuid} in project: ${projectName}, user: ${username}`)

      const success = await webhookService.delete(projectName, uuid, username)

      if (!success) {
        res.status(404).json({ error: `Webhook with UUID '${uuid}' not found or access denied` })
        return
      }

      res.status(200).json({ success: true, message: 'Webhook deleted successfully' })
    } catch (error) {
      console.error('Error deleting webhook:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to delete webhook: ${errorMessage}` })
    }
  })

  /**
   * POST /api/webhooks/:uuid/execute
   * Execute Webhook - UUID-based Programmatic AI Agent Interaction
   *
   * This endpoint enables external systems to trigger Coday AI agent interactions remotely
   * using pre-configured webhook definitions identified by UUID.
   *
   * IMPORTANT: This endpoint remains project-agnostic - the webhook service
   * finds the project automatically based on the webhook UUID.
   *
   * URL Parameters:
   * - uuid: string (required) - The UUID of the configured webhook
   *
   * Request Body:
   * - title: string (optional) - Title for the saved conversation thread
   * - prompts: string[] (required for 'free' type) - Array of prompts to execute
   * - awaitFinalAnswer: boolean (optional, default: false) - Whether to wait for completion
   * - [key: string]: any (for 'template' type) - Values to replace placeholders in template commands
   *
   * Response Modes:
   * - Async (awaitFinalAnswer: false): Returns immediately with threadId for fire-and-forget operations
   * - Sync (awaitFinalAnswer: true): Waits for all prompts to complete and returns final result
   *
   * Use Cases:
   * - CI/CD pipeline integration for automated code analysis
   * - External system integration for batch processing
   * - Scheduled tasks and monitoring workflows
   * - API-driven AI agent interactions with pre-configured templates
   */
  app.post('/api/webhooks/:uuid/execute', async (req: express.Request, res: express.Response) => {
    try {
      // Extract UUID from URL parameters
      const uuid = getParamAsString(req.params.uuid)
      if (!uuid) {
        debugLog('WEBHOOK', 'Missing UUID in request')
        res.status(400).send({ error: 'Missing webhook UUID in URL' })
        return
      }

      // Extract request body fields
      const { title, awaitFinalAnswer, ...parameters } = req.body

      // Use username that initiated the webhook call
      const username = getUsernameFn(req)

      if (!username) {
        res.status(401).send({ error: 'Username not found in request headers' })
        return
      }

      // Execute webhook via WebhookService (finds project automatically)
      const result = await webhookService.executeWebhook(uuid, parameters, username, title, awaitFinalAnswer)

      if (awaitFinalAnswer) {
        res.status(200).send(result)
      } else {
        res.status(201).send(result)
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      console.error('Error in webhook endpoint:', error)

      if (errorMessage.includes('not found')) {
        res.status(404).send({ error: errorMessage })
      } else if (errorMessage.includes('Missing or invalid') || errorMessage.includes('not configured')) {
        res.status(422).send({ error: errorMessage })
      } else {
        res.status(500).send({ error: 'Internal server error' })
      }
    }
  })
}
