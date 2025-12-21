import express from 'express'
import { debugLog } from './log'
import { WebhookService, Webhook } from '@coday/service'
import { ThreadCodayManager } from './thread-coday-manager'
import { CodayOptions } from '@coday/model'
import { CodayLogger } from '@coday/model'
import { CodayEvent, MessageEvent } from '@coday/model'
import { filter } from 'rxjs'
import { ThreadService } from '@coday/service'
import { getParamAsString } from './route-helpers'

/**
 * Webhook Management REST API Routes
 *
 * This module provides REST endpoints for managing webhooks through the web UI.
 * These endpoints complement the existing webhook execution endpoint (/api/webhook/:uuid).
 *
 * Endpoints:
 * - GET    /api/webhooks          - List all webhooks
 * - GET    /api/webhooks/:uuid    - Get specific webhook by UUID
 * - POST   /api/webhooks          - Create new webhook
 * - PUT    /api/webhooks/:uuid    - Update existing webhook
 * - DELETE /api/webhooks/:uuid    - Delete webhook
 *
 * TODO: Consider implementing JSON Schema validation (e.g., using Ajv) for more robust
 * and maintainable validation instead of manual checks. This would provide:
 * - Automatic validation with clear error messages
 * - Self-documenting API through schema
 * - Easier maintenance as requirements evolve
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
   * GET /api/webhooks
   * List all webhooks
   */
  app.get('/api/webhooks', async (_req: express.Request, res: express.Response) => {
    try {
      debugLog('WEBHOOK_API', 'GET all webhooks')
      const webhooks = await webhookService.list()
      res.status(200).json(webhooks)
    } catch (error) {
      console.error('Error listing webhooks:', error)
      res.status(500).json({ error: 'Failed to list webhooks' })
    }
  })

  /**
   * GET /api/webhooks/:uuid
   * Get specific webhook by UUID
   */
  app.get('/api/webhooks/:uuid', async (req: express.Request, res: express.Response) => {
    try {
      const uuid = getParamAsString(req.params.uuid)
      if (!uuid) {
        res.status(400).json({ error: 'Webhook UUID is required' })
        return
      }

      debugLog('WEBHOOK_API', `GET webhook: ${uuid}`)
      const webhook = await webhookService.get(uuid)

      if (!webhook) {
        res.status(404).json({ error: `Webhook with UUID '${uuid}' not found` })
        return
      }

      res.status(200).json(webhook)
    } catch (error) {
      console.error('Error retrieving webhook:', error)
      res.status(500).json({ error: 'Failed to retrieve webhook' })
    }
  })

  /**
   * POST /api/webhooks
   * Create new webhook
   */
  app.post('/api/webhooks', async (req: express.Request, res: express.Response) => {
    try {
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

      if (!webhookData.project || typeof webhookData.project !== 'string') {
        res.status(422).json({ error: 'Webhook project is required' })
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

      debugLog('WEBHOOK_API', `POST new webhook: ${webhookData.name}`)

      const newWebhook = await webhookService.create({
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
   * PUT /api/webhooks/:uuid
   * Update existing webhook
   */
  app.put('/api/webhooks/:uuid', async (req: express.Request, res: express.Response) => {
    try {
      const uuid = getParamAsString(req.params.uuid)
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

      debugLog('WEBHOOK_API', `PUT webhook: ${uuid}`)

      const updatedWebhook = await webhookService.update(uuid, updates)

      if (!updatedWebhook) {
        res.status(404).json({ error: `Webhook with UUID '${uuid}' not found` })
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
   * DELETE /api/webhooks/:uuid
   * Delete webhook
   */
  app.delete('/api/webhooks/:uuid', async (req: express.Request, res: express.Response) => {
    try {
      const uuid = getParamAsString(req.params.uuid)
      if (!uuid) {
        res.status(400).json({ error: 'Webhook UUID is required' })
        return
      }

      debugLog('WEBHOOK_API', `DELETE webhook: ${uuid}`)

      const success = await webhookService.delete(uuid)

      if (!success) {
        res.status(404).json({ error: `Webhook with UUID '${uuid}' not found` })
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

      // Execute webhook via WebhookService
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
