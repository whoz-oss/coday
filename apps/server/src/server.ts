import express from 'express'
import path from 'path'
import fs from 'fs'
import { ThreadCodayManager } from './lib/thread-coday-manager'

import * as os from 'node:os'
import { debugLog } from './lib/log'
import { CodayLoggerUtils } from '@coday/utils'
import {
  ThreadCleanupService,
  PromptService,
  SchedulerService,
  PromptExecutionService,
  ConfigServiceRegistry,
  ProjectService,
  ThreadFileService,
  ThreadService,
} from '@coday/service'
import { findAvailablePort } from './lib/find-available-port'
import { ServerInteractor } from '@coday/model'
import { registerConfigRoutes } from './lib/config.routes'
import { registerProjectRoutes } from './lib/project.routes'
import { registerThreadRoutes } from './lib/thread.routes'
import { registerMessageRoutes } from './lib/message.routes'
import { registerUserRoutes } from './lib/user.routes'
import { registerAgentRoutes } from './lib/agent.routes'
import { AgentCrudService } from '@coday/service'
import { registerPromptRoutes } from './lib/prompt.routes'
import { registerSchedulerRoutes } from './lib/scheduler.routes'
import { registerPromptExecutionRoutes } from './lib/prompt-execution.routes'
import { parseCodayOptions } from './lib/coday-options-utils'
import { ProjectFileRepository } from '@coday/repository'
import { McpInstancePool } from '@coday/mcp'

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
const logger = new CodayLoggerUtils(loggingEnabled, codayOptions.logFolder)
debugLog(
  'INIT',
  `Usage logging ${loggingEnabled ? 'enabled' : 'disabled'} ${codayOptions.logFolder ? `(custom folder: ${codayOptions.logFolder})` : ''}`
)

// Create webhook service instance (delegates to prompt execution)
debugLog('INIT', 'Webhook service initialized (will be initialized with prompt execution service)')

// Create prompt service instance and execution service
// Note: projectPath will be set after projectService is initialized
let promptService: PromptService
let promptExecutionService: PromptExecutionService
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

  // Verify client path exists
  if (!fs.existsSync(clientPath)) {
    console.error(`ERROR: Client path does not exist: ${clientPath}`)
    console.error('Please build the client first with: pnpm nx run client:build')
  } else {
    const indexPath = path.join(clientPath, 'index.html')
    if (!fs.existsSync(indexPath)) {
      console.error(`ERROR: index.html not found at: ${indexPath}`)
      console.error('Client build may be incomplete. Try rebuilding with: pnpm nx run client:build')
    } else {
      debugLog('INIT', `Verified index.html exists at ${indexPath}`)
    }
  }

  // Serve static files from the Angular build output
  app.use(express.static(clientPath))
}
// Initialize project service for REST API endpoints
const projectRepository = new ProjectFileRepository(codayOptions.configDir)

// Resolve the actual project ID (with hash) if we're in default mode
let resolvedProjectName = codayOptions.project
if (resolvedProjectName && !codayOptions.forcedProject) {
  // In default mode, check for existing project first
  const cwd = process.cwd()
  const basename = path.basename(cwd)

  // First, check if a non-volatile project exists with the simple name and matches the path
  if (projectRepository.exists(basename)) {
    const config = projectRepository.getConfig(basename)
    if (config && config.path === cwd && !config.volatile) {
      debugLog('INIT', `Default mode: found existing non-volatile project '${basename}' for current directory`)
      resolvedProjectName = basename
    } else {
      // Project exists but path doesn't match or is volatile, use volatile ID
      const volatileProjectId = ProjectService.generateProjectId(cwd)
      debugLog(
        'INIT',
        `Default mode: existing project '${basename}' doesn't match path, using volatile ID ${volatileProjectId}`
      )
      resolvedProjectName = volatileProjectId
    }
  } else {
    // No project with simple name, use volatile ID
    const volatileProjectId = ProjectService.generateProjectId(cwd)
    debugLog('INIT', `Default mode: no existing project found, using volatile ID ${volatileProjectId}`)
    resolvedProjectName = volatileProjectId
  }
}

const projectService = new ProjectService(projectRepository, resolvedProjectName, codayOptions.forcedProject)

promptService = new PromptService(codayOptions.configDir, projectService)
debugLog('INIT', 'Prompt service initialized')

// Create prompt execution service
promptExecutionService = new PromptExecutionService(promptService)
debugLog('INIT', 'Prompt execution service initialized (will be initialized with dependencies after thread manager)')

// Initialize thread file service for REST API endpoints
const projectsDir = path.join(codayOptions.configDir, 'projects')
const threadFileService = new ThreadFileService(projectsDir)

// Initialize thread service for REST API endpoints
const threadService = new ThreadService(projectRepository, projectsDir, threadFileService)

// Initialize MCP instance pool for shared MCP instances
const mcpPool = new McpInstancePool()
debugLog('INIT', 'MCP instance pool initialized')

// Initialize the thread-based Coday manager for SSE architecture
const threadCodayManager = new ThreadCodayManager(logger, projectService, threadService, promptService, mcpPool)

// Initialize prompt execution dependencies now that thread manager is ready
promptExecutionService.initialize(threadCodayManager, threadService, codayOptions, logger)
debugLog('INIT', 'Prompt execution service initialized')

debugLog('INIT', 'Webhook service initialized with prompt execution delegation')

// Initialize config service registry for REST API endpoints
const configInteractor = new ServerInteractor('config-api')
const configRegistry = new ConfigServiceRegistry(codayOptions.configDir, configInteractor)

/**
 * System and service account usernames that are forbidden for security reasons.
 * These accounts are commonly used in containers, CI/CD, and system services
 * and should never be used for multi-user applications as they would create
 * a shared account for all users.
 */
const FORBIDDEN_USERNAMES = [
  'root', // Unix/Linux superuser
  'admin', // Common administrative account
  'administrator', // Windows administrator
  'system', // Windows system account
  'daemon', // Unix daemon account
  'nobody', // Unix unprivileged account
  'node', // Common Node.js container user
  'app', // Generic application user
  'service', // Generic service account
  'docker', // Docker-related accounts
  'www-data', // Web server user (nginx, apache)
  'nginx', // Nginx user
  'apache', // Apache user
  'ansible', // Ansible user
] as const

/**
 * Extract username for authentication and logging purposes
 *
 * In authenticated mode, extracts username from the x-forwarded-email header
 * (typically set by reverse proxy or authentication middleware).
 * In non-authenticated mode (default), uses the local system username for development/testing.
 *
 * @param req - Express request object containing headers
 * @returns Username string for logging and thread ownership
 * @throws Error if username is a system/service account (security protection)
 */
function getUsername(req: express.Request): string {
  const username = codayOptions.auth ? (req.headers[EMAIL_HEADER] as string) : os.userInfo().username

  // Security check: prevent running as system/service accounts
  if (FORBIDDEN_USERNAMES.includes(username.toLowerCase() as any)) {
    throw new Error(
      `Security error: Cannot run with username "${username}". ` +
        'This appears to be a system or service account. ' +
        'When running locally, ensure you are running as a regular user account. ' +
        'When running in production, ensure authentication is properly configured with --auth flag.'
    )
  }

  return username
}

// Register user information routes
registerUserRoutes(app, getUsername)

// Register configuration management routes
registerConfigRoutes(app, configRegistry, getUsername)

// Note: Legacy webhook routes removed - use prompt routes instead
// Webhook execution is now handled by prompt-execution.routes.ts

// Register prompt management routes
registerPromptRoutes(app, promptService, getUsername)

// Register prompt execution routes (webhook execution)
registerPromptExecutionRoutes(app, promptExecutionService, getUsername)

// Register project management routes
registerProjectRoutes(app, projectService)

// Register thread management routes
registerThreadRoutes(app, threadService, threadFileService, threadCodayManager, getUsername, codayOptions)

// Register message management routes
registerMessageRoutes(app, threadCodayManager, getUsername)

// Register agent management routes
const agentCrudService = new AgentCrudService(codayOptions.configDir, projectService)
registerAgentRoutes(
  app,
  projectService,
  getUsername,
  codayOptions.configDir,
  logger,
  promptService,
  threadService,
  agentCrudService,
  codayOptions
)

// Initialize scheduler service early so routes can be registered before catch-all
const schedulerService = new SchedulerService(logger, promptService, codayOptions.configDir)

// Register scheduler routes (service will be initialized after server starts)
registerSchedulerRoutes(app, schedulerService, getUsername)

// Catch-all route for Angular client-side routing (MUST be after all API routes)
// In production mode, serve index.html for any non-API routes
// This allows Angular router to handle routes on page refresh
if (process.env.BUILD_ENV !== 'development') {
  // Reuse the clientPath that was already resolved and verified at startup
  const clientPath = process.env.CODAY_CLIENT_PATH
    ? path.resolve(process.env.CODAY_CLIENT_PATH)
    : path.resolve(__dirname, '../coday-client/browser')

  const indexPath = path.resolve(clientPath, 'index.html')
  debugLog('INIT', `Catch-all route will serve: ${indexPath}`)

  // Use a middleware instead of route pattern to catch all remaining requests
  app.use((req, res, next) => {
    // API routes should have been handled above, but double-check to avoid masking real 404s
    if (req.path.startsWith('/api') || req.path.startsWith('/events')) {
      res.status(404).send('Not found')
      return
    }
    debugLog('ROUTER', `Serving index.html for client route: ${req.path}`)

    // Read and send the file manually to avoid Express sendFile issues in bundled code
    fs.readFile(indexPath, 'utf8', (err, data) => {
      if (err) {
        debugLog('ERROR', `Failed to read index.html from ${indexPath}:`, err)
        debugLog('ERROR', `File exists check: ${fs.existsSync(indexPath)}`)
        next(err)
      } else {
        res.type('html').send(data)
      }
    })
  })
}

// Initialize thread cleanup service (server-only)
let cleanupService: ThreadCleanupService | null = null

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, _: express.NextFunction) => {
  debugLog('ERROR', `Request error on ${req.method} ${req.path}:`, err.message)
  console.error(err.stack)

  // Provide more detailed error in development
  if (process.env.BUILD_ENV === 'development') {
    res.status(500).json({
      error: 'Internal Server Error',
      message: err.message,
      stack: err.stack,
    })
  } else {
    res.status(500).send('Something went wrong!')
  }
})

// Use PORT_PROMISE to listen on the available port
PORT_PROMISE.then(async (PORT) => {
  // Set baseUrl if not already configured
  if (!codayOptions.baseUrl) {
    codayOptions.baseUrl = `http://localhost:${PORT}`
    debugLog('INIT', `Base URL set to: ${codayOptions.baseUrl}`)
  } else {
    debugLog('INIT', `Using configured base URL: ${codayOptions.baseUrl}`)
  }

  app.listen(PORT, () => {
    console.log(`Server is running on http://localhost:${PORT}`)
  })

  // Start thread cleanup service after server is running
  try {
    debugLog('CLEANUP', 'Starting thread cleanup service...')

    const projectsConfigPath = path.join(codayOptions.configDir, 'projects')

    cleanupService = new ThreadCleanupService(projectsConfigPath, logger)
    await cleanupService.start()
    debugLog('CLEANUP', 'Thread cleanup service started successfully')
  } catch (error) {
    console.error('Failed to start thread cleanup service:', error)
  }

  // Initialize and start scheduler service after server is running
  // Routes are already registered, just need to initialize the service
  try {
    debugLog('SCHEDULER', 'Initializing scheduler service...')

    await schedulerService.initialize()

    // Initialize execution dependencies
    schedulerService.initializeExecution(promptExecutionService)

    debugLog('SCHEDULER', 'Scheduler service initialized successfully')
  } catch (error) {
    console.error('Failed to initialize scheduler service:', error)
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
    // Stop scheduler service
    console.log('Stopping scheduler service...')
    schedulerService.stop()

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
