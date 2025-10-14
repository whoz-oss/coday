import express from 'express'
import path from 'path'
import {ServerClientManager} from './server-client'

import {AnswerEvent, CodayEvent, MessageEvent, ImageContent} from '@coday/coday-events'
// eslint-disable-next-line @nx/enforce-module-boundaries
import {processImageBuffer} from '../../../libs/function/image-processor'
import {parseCodayOptions} from '@coday/options'
import * as os from 'node:os'
import {debugLog} from './log'
import {CodayLogger} from '@coday/service/coday-logger'
import {WebhookService} from '@coday/service/webhook.service'
import {ThreadCleanupService} from '@coday/service/thread-cleanup.service'
import {findAvailablePort} from './find-available-port'
import {catchError, filter, firstValueFrom, lastValueFrom, of, timeout, withLatestFrom} from 'rxjs'
import {ConfigServiceRegistry} from '@coday/service/config-service-registry'
import {ServerInteractor} from '@coday/model/server-interactor'
import {registerConfigRoutes} from './config.routes'
import {registerWebhookRoutes} from './webhook.routes'

const app = express()
const DEFAULT_PORT = process.env.PORT ? parseInt(process.env.PORT) : 3000

// Dynamically find an available port
const PORT_PROMISE = findAvailablePort(DEFAULT_PORT)
const EMAIL_HEADER = 'x-forwarded-email'

// Parse options once for all clients
const codayOptions = parseCodayOptions()
debugLog('INIT', 'Coday options:', codayOptions)

// Create single usage logger instance for all clients
// Logging is enabled when --log flag is used and not in no-auth mode
const loggingEnabled = !codayOptions.noLog
const logger = new CodayLogger(loggingEnabled, codayOptions.logFolder)
debugLog(
  'INIT',
  `Usage logging ${loggingEnabled ? 'enabled' : 'disabled'} ${codayOptions.logFolder ? `(custom folder: ${codayOptions.logFolder})` : ''}`
)

// Create single webhook service instance for all clients
const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
const configPath = codayOptions.configDir ?? defaultConfigPath
const webhookService = new WebhookService(configPath)
debugLog('INIT', 'Webhook service initialized')
// Serve Angular app as default (root)
app.use(express.static(path.join(__dirname, '../client')))

// Basic route to test server setup
app.get('/', (_req: express.Request, res: express.Response) => {
  res.send('Server is up and running!')
})

// Middleware to parse JSON bodies with increased limit for image uploads
app.use(express.json({limit: '20mb'}))

// Initialize the client manager with usage logger and webhook service
const clientManager = new ServerClientManager(logger, webhookService)

// Initialize config service registry for REST API endpoints
const configInteractor = new ServerInteractor('config-api')
const configRegistry = new ConfigServiceRegistry(configPath, configInteractor)

/**
 * Extract username for authentication and logging purposes
 *
 * In authenticated mode, extracts username from the x-forwarded-email header
 * (typically set by reverse proxy or authentication middleware).
 * In no-auth mode, uses the local system username for development/testing.
 *
 * @param req - Express request object containing headers
 * @returns Username string for logging and thread ownership
 */
function getUsername(req: express.Request): string {
  return codayOptions.noAuth ? os.userInfo().username : (req.headers[EMAIL_HEADER] as string)
}

// Register configuration management routes
registerConfigRoutes(app, configRegistry, getUsername)

// Register webhook management routes
registerWebhookRoutes(app, webhookService, getUsername)

// Initialize thread cleanup service (server-only)
let cleanupService: ThreadCleanupService | null = null

/**
 * Webhook Endpoint - UUID-based Programmatic AI Agent Interaction
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
app.post('/api/webhook/:uuid', async (req: express.Request, res: express.Response) => {
  let clientId: string | null = null

  try {
    // Extract UUID from URL parameters
    const {uuid} = req.params
    if (!uuid) {
      debugLog('WEBHOOK', 'Missing UUID in request')
      res.status(400).send({error: 'Missing webhook UUID in URL'})
      return
    }

    // Load webhook configuration
    const webhook = await webhookService.get(uuid)
    if (!webhook) {
      debugLog('WEBHOOK', `Webhook not found for UUID: ${uuid}`)
      res.status(404).send({error: `Webhook with UUID '${uuid}' not found`})
      return
    }

    // Extract request body fields
    const {title, prompts: bodyPrompts, awaitFinalAnswer, ...placeholderValues} = req.body

    // Use webhook configuration
    const project = webhook.project

    // Use username that initiated the webhook call
    const username = getUsername(req)

    // Determine prompts based on webhook command type
    let prompts: string[]
    if (webhook.commandType === 'free') {
      // For 'free' type, use prompts from request body
      if (!bodyPrompts || !Array.isArray(bodyPrompts) || bodyPrompts.length === 0) {
        res.status(422).send({error: 'Missing or invalid prompts array for free command type'})
        return
      }
      prompts = bodyPrompts
    } else if (webhook.commandType === 'template') {
      // For 'template' type, use webhook commands with placeholder replacement
      if (!webhook.commands || webhook.commands.length === 0) {
        res.status(422).send({error: 'Webhook has no template commands configured'})
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
      res.status(500).send({error: `Unknown webhook command type: ${webhook.commandType}`})
      return
    }

    if (!project) {
      res.status(422).send({error: 'Webhook project not configured'})
      return
    }

    if (!username) {
      res.status(422).send({error: 'Webhook createdBy not configured'})
      return
    }

    // Generate unique client ID for this webhook request
    clientId = `webhook_${Math.random().toString(36).substring(2, 15)}`

    // Configure one-shot Coday instance with automatic thread saving
    const finalPrompts = title ? [`thread save ${title}`, ...prompts] : prompts

    const oneShotOptions = {
      ...codayOptions,
      oneshot: true, // Creates isolated instance that terminates after processing
      project, // Target project for the AI agent interaction
      prompts: finalPrompts, // Auto-save thread with title + user prompts
    }

    // Create a dedicated client instance for this webhook request
    const client = clientManager.getOrCreate(clientId, null, oneShotOptions, username)
    const interactor = client.getInteractor()
    client.startCoday()

    // Log successful webhook initiation with clientId for log correlation
    const logData = {
      project,
      title: title ?? 'Untitled',
      username,
      clientId,
      promptCount: finalPrompts.length,
      awaitFinalAnswer: !!awaitFinalAnswer,
      webhookName: webhook.name,
      webhookUuid: webhook.uuid,
    }
    logger.logWebhook(logData)

    const threadIdSource = client.getThreadId()

    if (awaitFinalAnswer) {
      // Synchronous mode: wait for completion
      const lastEventObservable = interactor.events.pipe(
        filter((event: CodayEvent) => event instanceof MessageEvent && event.role === 'assistant' && !!event.name)
      )

      lastValueFrom(lastEventObservable.pipe(withLatestFrom(threadIdSource)))
        .then(([lastEvent, threadId]) => {
          res.status(200).send({threadId, lastEvent})
        })
        .catch((error) => {
          const errorMessage = error instanceof Error ? error.message : 'Unknown error'
          logger.logWebhookError({
            error: `Webhook completion failed: ${errorMessage}`,
            username,
            project,
            clientId,
          })
          console.error('Error waiting for webhook completion:', error)

          // Don't trigger shutdown for webhook errors
          if (!res.headersSent) {
            res.status(500).send({error: 'Webhook processing failed'})
          }
        })
    } else {
      // Asynchronous mode: return immediately with thread ID
      firstValueFrom(
        threadIdSource.pipe(
          timeout(10000), // Increased timeout to 10 seconds
          catchError((error) => {
            // Special handling for EmptyError during system sleep
            if (error.message === 'no elements in sequence' || error.constructor?.name === 'EmptyErrorImpl') {
              console.log('Thread ID Observable empty during system sleep - webhook will continue')
              return of('unknown') // Return placeholder ID
            }
            // Handle timeout errors gracefully
            if (error.name === 'TimeoutError') {
              console.log('Thread ID timeout - webhook will continue with unknown ID')
              return of('unknown')
            }
            console.error('Error in thread ID observable:', error)
            return of('unknown') // Return placeholder for any error
          })
        )
      )
        .then((threadId) => {
          if (!res.headersSent) {
            res.status(201).send({threadId})
          }
        })
        .catch((error) => {
          // This catch should rarely be reached now due to comprehensive error handling above
          const errorMessage = error instanceof Error ? error.message : 'Unknown error'
          logger.logWebhookError({
            error: `Failed to get thread ID: ${errorMessage}`,
            username,
            project,
            clientId,
          })
          console.error('Error getting thread ID for webhook:', error)

          // Don't trigger shutdown for webhook errors
          if (!res.headersSent) {
            res.status(500).send({error: 'Failed to initialize webhook processing'})
          }
        })
    }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'
    logger.logWebhookError({
      error: `Webhook request failed: ${errorMessage}`,
      username: null,
      project: null,
      clientId,
    })
    console.error('Unexpected error in webhook endpoint:', error)
    res.status(500).send({error: 'Internal server error'})
  }
})

// POST endpoint for stopping the current run
app.post('/api/stop', (req: express.Request, res: express.Response) => {
  try {
    const clientId = req.query.clientId as string
    debugLog('STOP', `clientId: ${clientId}`)
    const client = clientManager.get(clientId)

    if (!client) {
      res.status(404).send('Client not found')
      return
    }

    client.updateLastConnection()
    client.stop()
    res.status(200).send('Stop signal sent successfully!')
  } catch (error) {
    console.error('Error processing stop request:', error)
    res.status(500).send('Error processing stop request')
  }
})

// POST endpoint for file uploads
app.post('/api/files/upload', async (req: express.Request, res: express.Response) => {
  try {
    const {clientId, content, mimeType, filename} = req.body

    // Validate required fields
    if (!clientId || !content || !mimeType || !filename) {
      res.status(400).json({error: 'Missing required fields: clientId, content, mimeType, filename'})
      return
    }

    debugLog('UPLOAD', `clientId: ${clientId}, uploading: ${filename}`)

    const client = clientManager.get(clientId)
    if (!client) {
      res.status(404).json({error: 'Client not found'})
      return
    }

    // Process the image
    const buffer = Buffer.from(content, 'base64')
    const processed = await processImageBuffer(buffer, mimeType)

    // Create ImageContent
    const imageContent: ImageContent = {
      type: 'image',
      content: processed.content,
      mimeType: processed.mimeType,
      width: processed.width,
      height: processed.height,
      source: `${filename} (${(processed.processedSize / 1024).toFixed(1)} KB)`
    }

    // Upload to thread via Coday
    if (!client.coday) {
      console.log('No Coday instance available')
      res.status(400).json({error: 'No active session available'})
      return
    }
    client.coday?.upload([imageContent])

    client.updateLastConnection()

    res.json({
      success: true,
      processedSize: processed.processedSize,
      dimensions: {width: processed.width, height: processed.height}
    })

  } catch (error) {
    console.error('Error processing file upload:', error)
    res.status(400).json({error: error instanceof Error ? error.message : 'Upload failed'})
  }
})



// POST endpoint for receiving AnswerEvent messages
app.post('/api/message', (req: express.Request, res: express.Response) => {
  try {
    const payload = req.body
    const clientId = req.query.clientId as string
    debugLog('MESSAGE', `clientId: ${clientId}, received message`)
    const client = clientManager.get(clientId)

    if (!client) {
      res.status(404).send('Client not found')
      return
    }

    client.getInteractor().sendEvent(new AnswerEvent(payload))
    client.updateLastConnection()

    res.status(200).send('Message received successfully!')
  } catch (error) {
    console.error('Error processing AnswerEvent:', error)
    res.status(400).send('Invalid event data!')
  }
})



// GET endpoint for retrieving full event details
app.get('/api/event/:eventId', (req: express.Request, res: express.Response) => {
  try {
    const {eventId} = req.params
    const clientId = req.query.clientId as string
    debugLog('EVENT', `clientId: ${clientId}, requesting event ${eventId}`)
    const client = clientManager.get(clientId)

    if (!client) {
      res.status(404).send('Client not found')
      return
    }
    if (!eventId) {
      res.status(400).send('Missing eventId')
      return
    }

    client.updateLastConnection()
    const event = client.getEventById(eventId)

    if (!event) {
      res.status(404).send('Event not found')
      return
    }

    // Set content type to plain text for easy viewing
    res.setHeader('Content-Type', 'text/plain')

    // Format and return the event details
    let output = ''

    if (event.type === 'tool_request') {
      output = `Tool Request: ${(event as any).name}\n\nArguments:\n${JSON.stringify(JSON.parse((event as any).args), null, 2)}`
    } else if (event.type === 'tool_response') {
      try {
        // Try to parse as JSON for pretty printing
        const parsedOutput = JSON.parse((event as any).output)
        output = `Tool Response:\n\n${JSON.stringify(parsedOutput, null, 2)}`
      } catch (e) {
        // If not valid JSON, return as is
        output = `Tool Response:\n\n${(event as any).output}`
      }
    } else {
      output = JSON.stringify(event, null, 2)
    }

    res.status(200).send(output)
  } catch (error) {
    console.error('Error retrieving event:', error)
    res.status(500).send('Error retrieving event')
  }
})

// GET endpoint for retrieving current session state
app.get('/api/session/state', async (req: express.Request, res: express.Response) => {
  try {
    const clientId = req.query.clientId as string
    debugLog('SESSION_STATE', `clientId: ${clientId}, requesting session state`)

    if (!clientId) {
      res.status(400).json({error: 'Client ID is required'})
      return
    }

    const client = clientManager.get(clientId)
    if (!client) {
      res.status(404).json({error: 'Client not found'})
      return
    }

    client.updateLastConnection()

    // Delegate to ServerClient for session state logic
    const sessionState = await client.getSessionState()
    res.status(200).json(sessionState)
  } catch (error) {
    console.error('Error retrieving session state:', error)
    res.status(500).json({error: 'Error retrieving session state'})
  }
})

// DELETE endpoint for message deletion (rewind/retry functionality)
app.delete('/api/thread/message/:eventId', async (req: express.Request, res: express.Response) => {
  try {
    const {eventId: rawEventId} = req.params
    const clientId = req.query.clientId as string

    // Decode the eventId in case it was URL encoded
    const eventId = rawEventId ? decodeURIComponent(rawEventId) : ''

    // Validate required parameters
    if (!eventId) {
      res.status(400).json({error: 'Message eventId is required'})
      return
    }

    if (!clientId) {
      res.status(400).json({error: 'Client ID is required'})
      return
    }

    // Get the client instance
    const client = clientManager.get(clientId)
    if (!client) {
      res.status(404).json({error: 'Client not found'})
      return
    }

    client.updateLastConnection()

    // Attempt to truncate the thread at the specified message
    const success = await client.truncateThreadAtMessage(eventId)

    if (success) {
      debugLog('DELETE_MESSAGE', `Successfully deleted message ${eventId} for client ${clientId}`)
      res.status(200).json({
        success: true,
        message: 'Message deleted successfully'
      })
    } else {
      res.status(400).json({
        error: 'Failed to delete message. Message may not exist, may not be a user message, may be the first message, or agent may be thinking.'
      })
    }

  } catch (error) {
    console.error('Error processing message deletion:', error)
    res.status(500).json({error: 'Internal server error during message deletion'})
  }
})

// Implement SSE for Heartbeat
app.get('/events', (req: express.Request, res: express.Response) => {
  const clientId = req.query.clientId as string

  debugLog('SSE', `New connection request for client ${clientId}`)

  // handle username header coming from auth (or local frontend) or local in noAuth
  const usernameHeaderValue = getUsername(req)
  debugLog('SSE', `Connection started, clientId: ${clientId}, username: ${usernameHeaderValue}`)
  if (!usernameHeaderValue || typeof usernameHeaderValue !== 'string') {
    debugLog('SSE', 'Rejected: No username provided')
    res.status(400).send(`Invalid or missing request headers '${EMAIL_HEADER}'.`)
    return
  }
  const username = usernameHeaderValue as string

  // handle clientId, identifying the browser session
  if (!clientId) {
    debugLog('SSE', 'Rejected: No client ID provided')
    res.status(400).send('Client ID is required')
    return
  }

  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')

  const client = clientManager.getOrCreate(clientId, res, codayOptions, username)
  // Handle client disconnect
  req.on('close', () => {
    debugLog('SSE', `Client ${clientId} disconnected`)
    client.terminate()
  })

  // Start Coday if it's a new client
  if (client.startCoday()) {
    debugLog('SSE', `New Coday instance started for client ${clientId}`)
  } else {
    debugLog('SSE', `Existing Coday instance reused for client ${clientId}`)
  }
})

// Error handling middleware
app.use((err: any, _: express.Request, res: express.Response, __: express.NextFunction) => {
  console.error(err.stack)
  res.status(500).send('Something went wrong!')
})

// Start cleanup interval for expired clients with reference for cleanup
const clientCleanupInterval = setInterval(() => clientManager.cleanupExpired(), 60000) // Check every minute

// Use PORT_PROMISE to listen on the available port
PORT_PROMISE.then(async (PORT) => {
  app.listen(PORT, () => {
    console.log(`Server is running on http://localhost:${PORT}`)
  })

  // Start thread cleanup service after server is running
  try {
    debugLog('CLEANUP', 'Starting thread cleanup service...')

    // Construire le chemin vers les projets (même logique que ProjectService)
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    const configPath = codayOptions.configDir ?? defaultConfigPath
    const projectsConfigPath = path.join(configPath, 'projects')

    cleanupService = new ThreadCleanupService(projectsConfigPath, logger)
    await cleanupService.start()
    debugLog('CLEANUP', 'Thread cleanup service started successfully')
  } catch (error) {
    console.error('Failed to start thread cleanup service:', error)
  }
}).catch((error) => {
  console.error('Failed to start server:', error)
  process.exit(1)
})

// Graceful shutdown with proper cleanup
let isShuttingDown = false

async function gracefulShutdown(signal: string) {
  if (isShuttingDown) {
    console.log(`Received ${signal} during shutdown, forcing exit...`)
    process.exit(1)
  }

  isShuttingDown = true
  console.log(`Received ${signal}, shutting down gracefully...`)

  try {
    // Stop accepting new connections and clear intervals
    console.log('Stopping client cleanup interval...')
    clearInterval(clientCleanupInterval)

    // Stop thread cleanup service
    if (cleanupService) {
      console.log('Stopping thread cleanup service...')
      await cleanupService.stop()
    }

    // Cleanup client manager resources
    console.log('Cleaning up client connections...')
    clientManager.shutdown()

    console.log('Graceful shutdown completed')
    process.exit(0)
  } catch (error) {
    console.error('Error during graceful shutdown:', error)
    process.exit(1)
  }
}

// Handle various termination signals
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'))
process.on('SIGINT', () => gracefulShutdown('SIGINT'))
process.on('SIGUSR2', () => gracefulShutdown('SIGUSR2')) // nodemon restart
process.on('SIGHUP', () => gracefulShutdown('SIGHUP'))   // terminal closed

// Handle uncaught exceptions to prevent hanging
process.on('uncaughtException', (error) => {
  console.error('Uncaught exception:', error)

  // Special handling for RxJS EmptyError during system sleep
  if (error.message === 'no elements in sequence' || error.constructor.name === 'EmptyErrorImpl') {
    console.log('Detected RxJS EmptyError during system sleep - this is expected behavior')
    console.log('Process will continue normally after system wake')
    return // Don't shutdown for this specific error
  }

  if (!isShuttingDown) {
    gracefulShutdown('uncaughtException')
  }
})

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled rejection at:', promise, 'reason:', reason)

  // Special handling for RxJS errors during system sleep
  if (reason && typeof reason === 'object') {
    const error = reason as any // Type assertion for error object
    if (error.message === 'no elements in sequence' || error.constructor?.name === 'EmptyErrorImpl') {
      console.log('Detected RxJS EmptyError rejection during system sleep - this is expected behavior')
      return // Don't shutdown for this specific error
    }

    // Handle timeout errors gracefully - these are expected during high load
    if (error.name === 'TimeoutError' || error.message?.includes('timeout')) {
      console.log('Detected timeout error - handling gracefully without shutdown')
      return // Don't shutdown for timeout errors
    }

    // Handle AbortError from timeouts
    if (error.name === 'AbortError' || error.message?.includes('aborted')) {
      console.log('Detected abort error - handling gracefully without shutdown')
      return // Don't shutdown for abort errors
    }
  }

  // Only shutdown for critical unhandled rejections
  console.error('Critical unhandled rejection detected')
  if (!isShuttingDown) {
    gracefulShutdown('unhandledRejection')
  }
})
