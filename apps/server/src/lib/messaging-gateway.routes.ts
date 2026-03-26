import express from 'express'
import { debugLog } from './log'
import { MessagingGatewayService } from '@coday/service'
import type { MessagingInboundEvent } from '@coday/model'

/**
 * Messaging Gateway REST API Routes
 *
 * Architecture:
 * - Generic inbound event endpoint: POST /api/messaging/event
 * - Username is provided by the connector (already resolved), NOT from the request headers
 * - The connector is trusted — no additional auth header resolution here
 *
 * Endpoint:
 * - POST /api/messaging/event - Receive an inbound event from a messaging platform connector
 */

/**
 * Register messaging gateway routes on the Express app.
 * @param app - Express application instance
 * @param messagingGatewayService - Messaging gateway service instance
 */
export function registerMessagingGatewayRoutes(
  app: express.Application,
  messagingGatewayService: MessagingGatewayService
): void {
  /**
   * POST /api/messaging/event
   * Receive an inbound event from an external messaging platform connector.
   *
   * The connector is responsible for resolving the user identity (username/email)
   * and enriching the event before forwarding it here. This endpoint does NOT
   * use x-forwarded-email or any auth header — the username comes from the body.
   *
   * Request Body (MessagingInboundEvent):
   * - source: string (required) — e.g., "SLACK", "DISCORD"
   * - username: string (required) — Coday username (email), resolved by connector
   * - message: string (required) — The user's message text
   * - projectName: string (required) — Project to execute in
   * - replyContext: Record<string, string> (required) — Opaque context for the reply
   * - eventType: string (optional) — e.g., "mentioned_in_channel", "direct_message"
   * - conversationContext: string (optional) — Recent transcript from the connector
   *
   * Response: 202 Accepted with { threadId }
   */
  app.post('/api/messaging/event', async (req: express.Request, res: express.Response) => {
    try {
      const body = req.body as Partial<MessagingInboundEvent>

      // Validate required fields
      const missing: string[] = []
      if (!body.source) missing.push('source')
      if (!body.username) missing.push('username')
      if (!body.message) missing.push('message')
      if (!body.projectName) missing.push('projectName')
      if (!body.replyContext || typeof body.replyContext !== 'object') missing.push('replyContext')

      if (missing.length > 0) {
        res.status(400).send({ error: `Missing required fields: ${missing.join(', ')}` })
        return
      }

      const event = body as MessagingInboundEvent

      debugLog('MESSAGING_GATEWAY', `Received event from ${event.source} for user ${event.username}`)

      const result = await messagingGatewayService.handleEvent(event)

      res.status(202).send(result)
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      console.error('[MESSAGING_GATEWAY] Error handling event:', error)

      if (errorMessage.includes('not initialized')) {
        res.status(503).send({ error: 'Service not available' })
      } else {
        res.status(500).send({ error: 'Internal server error' })
      }
    }
  })
}
