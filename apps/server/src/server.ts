import express from 'express'
import path from 'path'
import { ThreadCodayManager } from './thread-coday-manager'

import { parseCodayOptions } from '@coday/options'
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
import { ProjectService } from './services/project.service'
import { ThreadService } from './services/thread.service'
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
// Initialize project service for REST API endpoints
const projectRepository = new ProjectFileRepository(configPath)
const projectService = new ProjectService(projectRepository, codayOptions.project)

// Initialize thread service for REST API endpoints
const projectsDir = path.join(configPath, 'projects')
const threadService = new ThreadService(projectRepository, projectsDir)

// Initialize the thread-based Coday manager for SSE architecture
const threadCodayManager = new ThreadCodayManager(logger, webhookService, projectService, threadService)

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

// Register webhook management routes (including execution endpoint)
registerWebhookRoutes(app, webhookService, getUsername, threadService, threadCodayManager, codayOptions, logger)

// Register project management routes
registerProjectRoutes(app, projectService, codayOptions.project)

// Register thread management routes
registerThreadRoutes(app, threadService, threadCodayManager, getUsername, codayOptions)

// Register message management routes
registerMessageRoutes(app, threadCodayManager, getUsername)

// Initialize thread cleanup service (server-only)
let cleanupService: ThreadCleanupService | null = null

// Error handling middleware
app.use((err: any, _: express.Request, res: express.Response, __: express.NextFunction) => {
  console.error(err.stack)
  res.status(500).send('Something went wrong!')
})

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
    // Stop thread cleanup service
    if (cleanupService) {
      console.log('Stopping thread cleanup service...')
      await cleanupService.stop()
    }

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
