import express from 'express'
import { debugLog } from './log'
import { ThreadCodayManager } from './thread-coday-manager'
import { AnswerEvent, OAuthCallbackEvent, buildCodayEvent } from '@coday/model'

/**
 * Message Management REST API Routes
 *
 * This module provides REST endpoints for managing messages within threads.
 * Messages are CodayEvents stored in AiThread instances.
 *
 * Architecture:
 * - Uses ThreadCodayManager to access active Coday instances by threadId
 * - Operations on instance.coday.context.aiThread
 * - Events automatically propagated via SSE to connected clients
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/threads/:threadId/messages                  - List messages
 * - POST   /api/projects/:projectName/threads/:threadId/messages                  - Add message
 * - GET    /api/projects/:projectName/threads/:threadId/messages/:eventId         - Get message
 * - GET    /api/projects/:projectName/threads/:threadId/messages/:eventId/formatted - Get formatted message (temporary)
 * - DELETE /api/projects/:projectName/threads/:threadId/messages/:eventId         - Delete message (truncate)
 *
 * Authentication:
 * - Username extracted from x-forwarded-email header (set by auth proxy)
 * - Users can only access messages from their own threads
 *
 * Note: DELETE operations (truncate) are NOT propagated to other SSE clients.
 * This is an accepted limitation for this iteration.
 */

/**
 * Register message management routes on the Express app
 * @param app - Express application instance
 * @param threadCodayManager - ThreadCodayManager instance for accessing Coday instances
 * @param getUsernameFn - Function to extract username from request
 */
export function registerMessageRoutes(
  app: express.Application,
  threadCodayManager: ThreadCodayManager,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/projects/:projectName/threads/:threadId/messages
   * List all messages in a thread
   */
  app.get(
    '/api/projects/:projectName/threads/:threadId/messages',
    async (req: express.Request, res: express.Response) => {
      try {
        const { projectName, threadId } = req.params
        if (!projectName || !threadId) {
          res.status(400).json({ error: 'Project name and thread ID are required' })
          return
        }

        const username = getUsernameFn(req)
        if (!username) {
          res.status(401).json({ error: 'Authentication required' })
          return
        }

        debugLog('MESSAGE', `GET messages for thread: ${threadId} in project: ${projectName}`)

        // Get the thread instance
        const instance = threadCodayManager.get(threadId)
        if (!instance?.coday) {
          res.status(404).json({ error: `Thread '${threadId}' not found or not active` })
          return
        }

        // Verify thread ownership
        if (instance.username !== username) {
          res.status(403).json({ error: 'Access denied: thread belongs to another user' })
          return
        }

        // Get messages from AiThread
        const aiThread = instance.coday.context?.aiThread
        if (!aiThread) {
          res.status(500).json({ error: 'Thread not properly initialized' })
          return
        }

        const result = await aiThread.getMessages(undefined, undefined)
        const messages = result.messages

        res.status(200).json(messages)
      } catch (error) {
        console.error('Error listing messages:', error)
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        res.status(500).json({ error: `Failed to list messages: ${errorMessage}` })
      }
    }
  )

  /**
   * POST /api/projects/:projectName/threads/:threadId/messages
   * Send a message to a thread via the interactor
   *
   * Body: CodayEvent payload (AnswerEvent, OAuthCallbackEvent, etc.)
   * {
   *   type: string,
   *   ... event-specific properties
   * }
   */
  app.post(
    '/api/projects/:projectName/threads/:threadId/messages',
    async (req: express.Request, res: express.Response) => {
      try {
        const { projectName, threadId } = req.params
        const payload = req.body

        if (!projectName || !threadId) {
          res.status(400).send('Project name and thread ID are required')
          return
        }

        const username = getUsernameFn(req)
        if (!username) {
          res.status(401).send('Authentication required')
          return
        }

        debugLog(
          'MESSAGE',
          `threadId: ${threadId}, project: ${projectName}, received message of type: ${payload.type || 'answer'}`
        )

        const instance = threadCodayManager.get(threadId)
        if (!instance?.coday) {
          res.status(404).send('Thread not found or not connected')
          return
        }

        // Verify thread ownership
        if (instance.username !== username) {
          res.status(403).send('Access denied: thread belongs to another user')
          return
        }

        // Handle OAuth callback events specially
        if (payload.type === 'oauth_callback') {
          const oauthEvent = buildCodayEvent(payload) as OAuthCallbackEvent
          if (oauthEvent && instance.coday.services.agent) {
            debugLog('MESSAGE', `Routing OAuth callback for ${oauthEvent.integrationName}`)
            const toolbox = instance.coday.services.agent.toolbox
            if (toolbox && 'handleOAuthCallback' in toolbox) {
              await toolbox.handleOAuthCallback(oauthEvent)
              res.status(200).send('OAuth callback handled successfully!')
              return
            }
          } else {
            debugLog('MESSAGE', `No agent service available for OAuth callback routing`)
          }
        }

        // Default behavior: send as AnswerEvent
        instance.coday.interactor.sendEvent(new AnswerEvent(payload))

        res.status(200).send('Message received successfully!')
      } catch (error) {
        console.error('Error processing event:', error)
        res.status(400).send('Invalid event data!')
      }
    }
  )

  /**
   * GET /api/projects/:projectName/threads/:threadId/messages/:eventId
   * Get a specific message by event ID
   */
  app.get(
    '/api/projects/:projectName/threads/:threadId/messages/:eventId',
    async (req: express.Request, res: express.Response) => {
      try {
        const { projectName, threadId, eventId: rawEventId } = req.params
        if (!projectName || !threadId || !rawEventId) {
          res.status(400).json({ error: 'Project name, thread ID, and event ID are required' })
          return
        }

        // Decode eventId in case it was URL encoded
        const eventId = decodeURIComponent(rawEventId)

        const username = getUsernameFn(req)
        if (!username) {
          res.status(401).json({ error: 'Authentication required' })
          return
        }

        debugLog('MESSAGE', `GET message: ${eventId} from thread: ${threadId} in project: ${projectName}`)

        // Get the thread instance
        const instance = threadCodayManager.get(threadId)
        if (!instance?.coday) {
          res.status(404).json({ error: `Thread '${threadId}' not found or not active` })
          return
        }

        // Verify thread ownership
        if (instance.username !== username) {
          res.status(403).json({ error: 'Access denied: thread belongs to another user' })
          return
        }

        // Get AiThread
        const aiThread = instance.coday.context?.aiThread
        if (!aiThread) {
          res.status(500).json({ error: 'Thread not properly initialized' })
          return
        }

        // Get message by eventId
        const message = aiThread.getEventById(eventId)

        if (!message) {
          res.status(404).json({ error: `Message '${eventId}' not found in thread '${threadId}'` })
          return
        }

        res.status(200).json(message)
      } catch (error) {
        console.error('Error retrieving message:', error)
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        res.status(500).json({ error: `Failed to retrieve message: ${errorMessage}` })
      }
    }
  )

  /**
   * GET /api/projects/:projectName/threads/:threadId/messages/:eventId/formatted
   * Get formatted message for display (temporary endpoint)
   *
   * This endpoint formats tool_request and tool_response events as human-readable text.
   * It's a temporary solution until the frontend can display these events properly.
   *
   * TODO: Remove this endpoint once frontend has proper event display components
   */
  app.get(
    '/api/projects/:projectName/threads/:threadId/messages/:eventId/formatted',
    async (req: express.Request, res: express.Response) => {
      try {
        const { projectName, threadId, eventId: rawEventId } = req.params
        if (!projectName || !threadId || !rawEventId) {
          res.status(400).send('Project name, thread ID, and event ID are required')
          return
        }

        // Decode eventId in case it was URL encoded
        const eventId = decodeURIComponent(rawEventId)

        const username = getUsernameFn(req)
        if (!username) {
          res.status(401).send('Authentication required')
          return
        }

        debugLog('MESSAGE', `GET formatted message: ${eventId} from thread: ${threadId} in project: ${projectName}`)

        // Get the thread instance
        const instance = threadCodayManager.get(threadId)
        if (!instance?.coday) {
          res.status(404).send('Thread not found or not active')
          return
        }

        // Verify thread ownership
        if (instance.username !== username) {
          res.status(403).send('Access denied: thread belongs to another user')
          return
        }

        // Get AiThread
        const aiThread = instance.coday.context?.aiThread
        if (!aiThread) {
          res.status(500).send('Thread not properly initialized')
          return
        }

        // Get message by eventId
        const event = aiThread.getEventById(eventId)

        if (!event) {
          res.status(404).send(`Message '${eventId}' not found in thread '${threadId}'`)
          return
        }

        // Set content type to plain text for easy viewing
        res.setHeader('Content-Type', 'text/plain')

        // Format and return the event details
        let output = ''

        if (event.type === 'tool_request') {
          output = `Tool Request: ${(event as any).name}\n\nArguments:\n${JSON.stringify(JSON.parse((event as any).args), null, 2)}`
        } else if (event.type === 'tool_response') {
          const eventOutput = (event as any).output

          // Check if output is already an object
          if (typeof eventOutput === 'object' && eventOutput !== null) {
            output = `Tool Response:\n\n${JSON.stringify(eventOutput, null, 2)}`
          } else if (typeof eventOutput === 'string') {
            try {
              // Try to parse as JSON for pretty printing
              const parsedOutput = JSON.parse(eventOutput)
              output = `Tool Response:\n\n${JSON.stringify(parsedOutput, null, 2)}`
            } catch (e) {
              // If not valid JSON, return as is
              output = `Tool Response:\n\n${eventOutput}`
            }
          } else {
            // Fallback for other types (number, boolean, etc.)
            output = `Tool Response:\n\n${String(eventOutput)}`
          }
        } else {
          output = JSON.stringify(event, null, 2)
        }

        res.status(200).send(output)
      } catch (error) {
        console.error('Error retrieving formatted message:', error)
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        res.status(500).send(`Failed to retrieve formatted message: ${errorMessage}`)
      }
    }
  )

  /**
   * DELETE /api/projects/:projectName/threads/:threadId/messages/:eventId
   * Delete a message (truncate thread at this message)
   *
   * Note: This operation is NOT propagated to other SSE clients.
   * This is an accepted limitation for this iteration.
   */
  app.delete(
    '/api/projects/:projectName/threads/:threadId/messages/:eventId',
    async (req: express.Request, res: express.Response) => {
      try {
        const { projectName, threadId, eventId: rawEventId } = req.params
        if (!projectName || !threadId || !rawEventId) {
          res.status(400).json({ error: 'Project name, thread ID, and event ID are required' })
          return
        }

        // Decode eventId in case it was URL encoded
        const eventId = decodeURIComponent(rawEventId)

        const username = getUsernameFn(req)
        if (!username) {
          res.status(401).json({ error: 'Authentication required' })
          return
        }

        debugLog(
          'MESSAGE',
          `DELETE
          message:
          ${eventId}
          from
          thread
          :
          ${threadId}
          in
          project
          :
          ${projectName}`
        )

        // Get the thread instance
        const instance = threadCodayManager.get(threadId)
        if (!instance?.coday) {
          res.status(404).json({ error: `Thread '${threadId}' not found or not active` })
          return
        }

        // Verify thread ownership
        if (instance.username !== username) {
          res.status(403).json({ error: 'Access denied: thread belongs to another user' })
          return
        }

        // Get AiThread
        const aiThread = instance.coday.context?.aiThread
        if (!aiThread) {
          res.status(500).json({ error: 'Thread not properly initialized' })
          return
        }

        // Attempt to truncate at the specified message
        const success = aiThread.truncateAtUserMessage(eventId)

        if (success) {
          debugLog('MESSAGE', `Successfully truncated thread ${threadId} at message ${eventId}`)
          res.status(200).json({
            success: true,
            message: 'Message deleted successfully',
          })
        } else {
          res.status(400).json({
            error:
              'Failed to delete message. Message may not exist, may not be a user message, or may be the first message.',
          })
        }
      } catch (error) {
        console.error('Error deleting message:', error)
        const errorMessage = error instanceof Error ? error.message : 'Unknown error'
        res.status(500).json({ error: `Failed to delete message: ${errorMessage}` })
      }
    }
  )
}
