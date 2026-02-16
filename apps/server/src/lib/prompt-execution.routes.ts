import express from 'express'
import { debugLog } from './log'
import { PromptExecutionService } from '@coday/service'
import { getParamAsString } from './route-helpers'

/**
 * Prompt Execution REST API Routes
 *
 * Architecture:
 * - Webhook execution endpoint: /api/webhooks/:promptId/execute
 * - Finds prompt automatically across all projects
 * - Validates webhookEnabled flag
 * - Executes with provided username
 *
 * Endpoint:
 * - POST /api/webhooks/:promptId/execute - Execute prompt via webhook
 */

/**
 * Register prompt execution routes on the Express app
 * @param app - Express application instance
 * @param promptExecutionService - Prompt execution service instance
 * @param getUsernameFn - Function to extract username from request
 */
export function registerPromptExecutionRoutes(
  app: express.Application,
  promptExecutionService: PromptExecutionService,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * POST /api/webhooks/:promptId/execute
   * Execute Prompt via Webhook - ID-based Programmatic AI Agent Interaction
   *
   * This endpoint enables external systems to trigger Coday AI agent interactions remotely
   * using pre-configured prompt definitions identified by ID.
   *
   * IMPORTANT: This endpoint is project-agnostic - the prompt execution service
   * finds the project automatically based on the prompt ID.
   *
   * URL Parameters:
   * - promptId: string (required) - The ID of the configured prompt
   *
   * Request Body:
   * - title: string (optional) - Title for the saved conversation thread
   * - awaitFinalAnswer: boolean (optional, default: false) - Whether to wait for completion
   * - [key: string]: any - Values to replace placeholders in template commands
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
  app.post('/api/webhooks/:promptId/execute', async (req: express.Request, res: express.Response) => {
    try {
      // Extract promptId from URL parameters
      const promptId = getParamAsString(req.params.promptId)
      if (!promptId) {
        debugLog('WEBHOOK', 'Missing promptId in request')
        res.status(400).send({ error: 'Missing prompt ID in URL' })
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

      debugLog('WEBHOOK', `Executing prompt ${promptId} for user ${username}`)

      // Execute prompt via PromptExecutionService (finds project automatically)
      const result = await promptExecutionService.executePrompt(promptId, parameters, username, 'webhook', {
        title,
        awaitFinalAnswer,
      })

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
      } else if (errorMessage.includes('not enabled for webhook')) {
        res.status(403).send({ error: errorMessage })
      } else if (
        errorMessage.includes('Missing required parameters') ||
        errorMessage.includes('contains structured placeholders') ||
        errorMessage.includes('Missing or invalid') ||
        errorMessage.includes('not configured')
      ) {
        res.status(422).send({ error: errorMessage })
      } else {
        res.status(500).send({ error: 'Internal server error' })
      }
    }
  })
}
