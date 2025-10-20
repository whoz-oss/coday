import express from 'express'
import path from 'path'
import { ServerClientManager } from './server-client'
import { ThreadCodayManager } from './thread-coday-manager'

import { AnswerEvent, ImageContent } from '@coday/coday-events'
// eslint-disable-next-line @nx/enforce-module-boundaries
import { processImageBuffer } from '../../../libs/function/image-processor'
import { CodayOptions, parseCodayOptions } from '@coday/options'
import * as os from 'node:os'
import { debugLog } from './log'
import { CodayLogger } from '@coday/service/coday-logger'
import { WebhookService } from '@coday/service/webhook.service'
import { ThreadCleanupService } from '@coday/service/thread-cleanup.service'
import { findAvailablePort } from './find-available-port'
import { ConfigServiceRegistry } from '@coday/service/config-service-registry'
import { ServerInteractor } from '@coday/model/server-interactor'
import { registerConfigRoutes } from './config.routes'
import { registerWebhookRoutes } from './webhook.routes'
import { registerProjectRoutes } from './project.routes'
import { registerThreadRoutes } from './thread.routes'
import { registerMessageRoutes } from './message.routes'
import { ProjectService2 } from './services/project.service2'
import { ThreadService2 } from './services/thread.service2'
import { ProjectFileRepository } from '@coday/repository/project-file.repository'

const app = express()
const DEFAULT_PORT = process.env.PORT
  ? parseInt(process.env.PORT)
  : process.env.BUILD_ENV === 'development'
    ? 4100
    : 3000

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
// Middleware to parse JSON bodies with increased limit for image uploads
app.use(express.json({ limit: '20mb' }))

// Development mode: proxy to Angular dev server
if (process.env.BUILD_ENV === 'development') {
  const ANGULAR_DEV_SERVER = 'http://localhost:4200'
  debugLog('INIT', `Development mode: proxying to Angular dev server at ${ANGULAR_DEV_SERVER}`)

  // Import http-proxy-middleware dynamically
  import('http-proxy-middleware')
    .then(({ createProxyMiddleware }) => {
      // Proxy all non-API requests to Angular dev server
      const proxyMiddleware = createProxyMiddleware({
        target: ANGULAR_DEV_SERVER,
        changeOrigin: true,
        ws: true, // Enable WebSocket proxying for Angular HMR
      })

      // Only proxy if not an API route
      app.use('/', (req, res, next) => {
        if (req.path.startsWith('/api') || req.path.startsWith('/events')) {
          next()
        } else {
          proxyMiddleware(req, res, next)
        }
      })

      debugLog('INIT', 'Proxy middleware configured successfully')
    })
    .catch((error) => {
      console.error('Failed to load http-proxy-middleware:', error)
    })
} else {
  // Production mode: serve static Angular app
  // Check for CODAY_CLIENT_PATH environment variable (set by web launcher)
  // Otherwise fall back to relative path (for standalone server usage)
  const clientPath = process.env.CODAY_CLIENT_PATH
    ? path.resolve(process.env.CODAY_CLIENT_PATH)
    : path.resolve(__dirname, '../coday-client/browser')

  debugLog('INIT', `Production mode: serving static files from ${clientPath}`)
  if (process.env.CODAY_CLIENT_PATH) {
    debugLog('INIT', 'Using client path from CODAY_CLIENT_PATH environment variable')
  }

  // Serve static files from the Angular build output
  app.use(express.static(clientPath))
}

// Initialize the client manager with usage logger and webhook service
const clientManager = new ServerClientManager(logger, webhookService)

// Initialize the thread-based Coday manager for new SSE architecture
const threadCodayManager = new ThreadCodayManager(logger, webhookService)

// Initialize config service registry for REST API endpoints
const configInteractor = new ServerInteractor('config-api')
const configRegistry = new ConfigServiceRegistry(configPath, configInteractor)

// Initialize project service for REST API endpoints
const projectRepository = new ProjectFileRepository(configPath)
const projectService = new ProjectService2(projectRepository, codayOptions.project)

// Initialize thread service for REST API endpoints
const projectsDir = path.join(configPath, 'projects')
const threadService = new ThreadService2(projectRepository, projectsDir)

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

// Register webhook management routes (including execution endpoint)
registerWebhookRoutes(app, webhookService, getUsername, threadService, threadCodayManager, codayOptions, logger)

// Register project management routes
registerProjectRoutes(app, projectService, codayOptions.project)

// Register thread management routes
registerThreadRoutes(app, threadService, threadCodayManager, getUsername)

// Register message management routes
registerMessageRoutes(app, threadCodayManager, getUsername)

// Initialize thread cleanup service (server-only)
let cleanupService: ThreadCleanupService | null = null

// POST endpoint for file uploads
app.post('/api/files/upload', async (req: express.Request, res: express.Response) => {
  try {
    const { clientId, content, mimeType, filename } = req.body
    // Validate required fields
    if (!clientId || !content || !mimeType || !filename) {
      res.status(400).json({ error: 'Missing required fields: clientId, content, mimeType, filename' })
      return
    }

    debugLog('UPLOAD', `clientId: ${clientId}, uploading: ${filename}`)

    const client = clientManager.get(clientId)
    if (!client) {
      res.status(404).json({ error: 'Client not found' })
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
      source: `${filename} (${(processed.processedSize / 1024).toFixed(1)} KB)`,
    }

    // Upload to thread via Coday
    if (!client.coday) {
      console.log('No Coday instance available')
      res.status(400).json({ error: 'No active session available' })
      return
    }
    client.coday?.upload([imageContent])

    client.updateLastConnection()

    res.json({
      success: true,
      processedSize: processed.processedSize,
      dimensions: { width: processed.width, height: processed.height },
    })
  } catch (error) {
    console.error('Error processing file upload:', error)
    res.status(400).json({ error: error instanceof Error ? error.message : 'Upload failed' })
  }
})

// POST endpoint for sending messages to a specific thread (new architecture)
app.post('/api/projects/:projectName/threads/:threadId/message', (req: express.Request, res: express.Response) => {
  try {
    const projectName = req.params['projectName']
    const threadId = req.params['threadId']
    const payload = req.body

    if (!projectName || !threadId) {
      res.status(400).send('Project name and thread ID are required')
      return
    }

    debugLog('MESSAGE', `threadId: ${threadId}, project: ${projectName}, received message`)

    const instance = threadCodayManager.get(threadId)
    if (!instance?.coday) {
      res.status(404).send('Thread not found or not connected')
      return
    }

    instance.coday.interactor.sendEvent(new AnswerEvent(payload))

    res.status(200).send('Message received successfully!')
  } catch (error) {
    console.error('Error processing AnswerEvent:', error)
    res.status(400).send('Invalid event data!')
  }
})

// GET endpoint for retrieving full event details
app.get('/api/event/:eventId', (req: express.Request, res: express.Response) => {
  try {
    const { eventId } = req.params
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

/**
 * New SSE endpoint for thread-based event streaming
 * GET /api/projects/:projectName/threads/:threadId/event-stream
 *
 * This endpoint provides Server-Sent Events for a specific thread.
 * The Coday instance is indexed by threadId, allowing multiple users
 * to potentially connect to the same thread in the future.
 *
 * Authentication: Username extracted from x-forwarded-email header
 * Validation: Thread must exist and belong to the authenticated user
 */
app.get(
  '/api/projects/:projectName/threads/:threadId/event-stream',
  async (req: express.Request, res: express.Response) => {
    const projectName = req.params['projectName']
    const threadId = req.params['threadId']
    const username = getUsername(req)

    // Validate required parameters
    if (!projectName || !threadId) {
      res.status(400).send('Project name and thread ID are required')
      return
    }

    debugLog('THREAD_SSE', `New connection request for thread ${threadId} in project ${projectName}`)

    // Validate authentication
    // TODO: factorize
    if (!username) {
      debugLog('THREAD_SSE', 'Rejected: No username provided')
      res.status(401).send('Authentication required')
      return
    }

    // Setup SSE headers
    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')

    // Create options with project and thread pre-selected
    const threadOptions: CodayOptions = {
      ...codayOptions,
      project: projectName,
      thread: threadId,
    }

    // Get or create thread-based Coday instance
    const instance = threadCodayManager.getOrCreate(threadId, projectName, username, threadOptions, res)

    // Handle client disconnect
    req.on('close', () => {
      debugLog('THREAD_SSE', `Client disconnected from thread ${threadId}`)
      threadCodayManager.removeConnection(threadId, res)
    })

    // Start Coday if it's a new instance
    if (instance.startCoday()) {
      debugLog('THREAD_SSE', `New Coday instance started for thread ${threadId}`)
    } else {
      debugLog('THREAD_SSE', `Reconnected to existing Coday instance for thread ${threadId}`)
    }
  }
)

// Implement SSE for Heartbeat (LEGACY - kept for backward compatibility)
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

    // Cleanup thread-based Coday instances
    console.log('Cleaning up thread Coday instances...')
    await threadCodayManager.shutdown()

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
process.on('SIGHUP', () => gracefulShutdown('SIGHUP')) // terminal closed

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
