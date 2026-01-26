import express from 'express'
import { debugLog } from './log'
import { WebhookService, Webhook } from '@coday/service'
import { ThreadCodayManager } from './thread-coday-manager'
import { CodayOptions } from '../../../libs/model/src/lib/coday-options'
import { CodayLogger } from '@coday/service'
import { CodayEvent, MessageEvent } from '@coday/model'
import { filter } from 'rxjs'
import { ThreadService } from '@coday/service'

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
 * @param webhookService - Webhook service instance for CRUD operations
 * @param getUsernameFn - Function to extract username from request (for createdBy field)
 * @param threadService - Thread service for creating threads
 * @param threadCodayManager - Thread Coday manager for webhook execution
 * @param codayOptions - Coday options for webhook execution
 * @param logger - Logger instance for webhook execution
 */
export function registerWebhookRoutes(
  app: express.Application,
  webhookService: WebhookService,
  getUsernameFn: (req: express.Request) => string,
  threadService: ThreadService,
  threadCodayManager: ThreadCodayManager,
  codayOptions: CodayOptions,
  logger: CodayLogger
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
      const { uuid } = req.params
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
      const { uuid } = req.params
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
      const { uuid } = req.params
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
    let threadId: string | null = null

    try {
      // Extract UUID from URL parameters
      const { uuid } = req.params
      if (!uuid) {
        debugLog('WEBHOOK', 'Missing UUID in request')
        res.status(400).send({ error: 'Missing webhook UUID in URL' })
        return
      }

      // Load webhook configuration
      const webhook = await webhookService.get(uuid)
      if (!webhook) {
        debugLog('WEBHOOK', `Webhook not found for UUID: ${uuid}`)
        res.status(404).send({ error: `Webhook with UUID '${uuid}' not found` })
        return
      }

      // Extract request body fields
      const { title, prompts: bodyPrompts, awaitFinalAnswer, ...placeholderValues } = req.body

      // Use webhook configuration
      const project = webhook.project

      // Use username that initiated the webhook call
      const username = getUsernameFn(req)

      // Determine prompts based on webhook command type
      let prompts: string[]
      if (webhook.commandType === 'free') {
        // For 'free' type, use prompts from request body
        if (!bodyPrompts || !Array.isArray(bodyPrompts) || bodyPrompts.length === 0) {
          res.status(422).send({ error: 'Missing or invalid prompts array for free command type' })
          return
        }
        prompts = bodyPrompts
      } else if (webhook.commandType === 'template') {
        // For 'template' type, use webhook commands with placeholder replacement
        if (!webhook.commands || webhook.commands.length === 0) {
          res.status(422).send({ error: 'Webhook has no template commands configured' })
          return
        }

        // Replace placeholders in template commands
        prompts = webhook.commands.map((command) => {
          let processedCommand = command
          // Simple string replacement for placeholders like {{key}}
          Object.entries(placeholderValues).forEach(([key, value]) => {
            const placeholder = `{{${key}}}`
            processedCommand = processedCommand.replace(new RegExp(placeholder, 'g'), String(value))
          })
          return processedCommand
        })
      } else {
        res.status(500).send({ error: `Unknown webhook command type: ${webhook.commandType}` })
        return
      }

      if (!project) {
        res.status(422).send({ error: 'Webhook project not configured' })
        return
      }

      if (!username) {
        res.status(422).send({ error: 'Webhook createdBy not configured' })
        return
      }

      // Create a new thread for the webhook
      const thread = await threadService.createThread(project, username, title)
      threadId = thread.id

      // Configure one-shot Coday instance with automatic prompts
      const oneShotOptions: CodayOptions = {
        ...codayOptions,
        oneshot: true, // Creates isolated instance that terminates after processing
        project, // Target project for the AI agent interaction
        thread: threadId, // Use the newly created thread
        prompts: prompts, // User prompts
      }

      debugLog('WEBHOOK', `Creating webhook instance with ${prompts.length} prompts:`, prompts)

      // Create a thread-based Coday instance for this webhook (without SSE connection)
      const instance = threadCodayManager.createWithoutConnection(threadId, project, username, oneShotOptions)

      // IMPORTANT: Prepare Coday without starting run() to subscribe to events first
      // Because interactor.events is a Subject (not ReplaySubject), we must subscribe BEFORE run() emits events
      instance.prepareCoday()
      const interactor = instance.coday!.interactor

      // Log successful webhook initiation
      const logData = {
        project,
        title: title ?? 'Untitled',
        username,
        clientId: threadId, // Use threadId as clientId for log correlation
        promptCount: prompts.length,
        awaitFinalAnswer: !!awaitFinalAnswer,
        webhookName: webhook.name,
        webhookUuid: webhook.uuid,
      }
      logger.logWebhook(logData)

      if (awaitFinalAnswer) {
        // Synchronous mode: wait for completion
        // Collect all assistant messages during the run
        const assistantMessages: MessageEvent[] = []
        const subscription = interactor.events
          .pipe(
            filter((event: CodayEvent) => {
              debugLog(
                'WEBHOOK',
                `Received event type: ${event.type}, role: ${event instanceof MessageEvent ? event.role : 'N/A'}`
              )
              return event instanceof MessageEvent && event.role === 'assistant' && !!event.name
            })
          )
          .subscribe((event) => {
            assistantMessages.push(event as MessageEvent)
          })

        // Now start Coday run and wait for it to complete
        instance
          .coday!.run()
          .catch((error) => {
            console.error('Error during webhook Coday run:', error)
          })
          .then(() => {
            subscription.unsubscribe()
            const lastEvent = assistantMessages[assistantMessages.length - 1]
            return lastEvent
          })
          .then((lastEvent) => {
            // Cleanup the thread instance after completion
            threadCodayManager.cleanup(threadId!).catch((error) => {
              console.error('Error cleaning up webhook thread:', error)
            })
            res.status(200).send({ threadId, lastEvent })
          })
          .catch((error) => {
            const errorMessage = error instanceof Error ? error.message : 'Unknown error'
            logger.logWebhookError({
              error: `Webhook completion failed: ${errorMessage}`,
              username,
              project,
              clientId: threadId,
            })
            console.error('Error waiting for webhook completion:', error)

            // Cleanup on error
            if (threadId) {
              threadCodayManager.cleanup(threadId).catch((cleanupError) => {
                console.error('Error cleaning up webhook thread after error:', cleanupError)
              })
            }

            if (!res.headersSent) {
              res.status(500).send({ error: 'Webhook processing failed' })
            }
          })
      } else {
        // Asynchronous mode: return immediately with thread ID
        // Start Coday run in background
        instance.coday!.run().catch((error) => {
          console.error('Error during webhook Coday run:', error)
        })

        // Schedule cleanup after a reasonable timeout (e.g., 5 minutes)
        setTimeout(
          () => {
            if (threadId) {
              threadCodayManager.cleanup(threadId).catch((error) => {
                console.error('Error cleaning up webhook thread after timeout:', error)
              })
            }
          },
          5 * 60 * 1000
        ) // 5 minutes

        res.status(201).send({ threadId })
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      logger.logWebhookError({
        error: `Webhook request failed: ${errorMessage}`,
        username: null,
        project: null,
        clientId: threadId,
      })
      console.error('Unexpected error in webhook endpoint:', error)

      // Cleanup on error
      if (threadId) {
        threadCodayManager.cleanup(threadId).catch((cleanupError) => {
          console.error('Error cleaning up webhook thread after error:', cleanupError)
        })
      }

      res.status(500).send({ error: 'Internal server error' })
    }
  })
}
