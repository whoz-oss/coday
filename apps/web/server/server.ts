import express from 'express'
import path from 'path'
import { ServerClientManager } from './server-client'

import { AnswerEvent, CodayEvent, MessageEvent } from '@coday/coday-events'
import { CodayOptions, parseCodayOptions } from '@coday/options'
import * as os from 'node:os'
import { debugLog } from './log'
import { CodayLogger } from '@coday/service/coday-logger'
import { ThreadCleanupService } from '@coday/service/thread-cleanup.service'
import { findAvailablePort } from './find-available-port'
import { filter, firstValueFrom, lastValueFrom, withLatestFrom } from 'rxjs'

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
const loggingEnabled = codayOptions.log || !codayOptions.noAuth
const logger = new CodayLogger(loggingEnabled, codayOptions.logFolder)
debugLog(
  'INIT',
  `Usage logging ${loggingEnabled ? 'enabled' : 'disabled'} ${codayOptions.logFolder ? `(custom folder: ${codayOptions.logFolder})` : ''}`
)
// Serve static files from the 'static' directory
app.use(express.static(path.join(__dirname, '../client')))

// Basic route to test server setup
app.get('/', (req: express.Request, res: express.Response) => {
  res.send('Server is up and running!')
})

// Middleware to parse JSON bodies
app.use(express.json())

// Initialize the client manager with usage logger
const clientManager = new ServerClientManager(logger)

// Initialize thread cleanup service (server-only)
let cleanupService: ThreadCleanupService | null = null

// should take as input:
// - project: string, the project name
// - title: string, optional, title of the thread for
// - prompts: array of strings, the prompts to run sequentially
// - shouldWait: boolean (optional), whether or not to wait for the final message
app.post('/api/webhook', (req: express.Request, res: express.Response) => {
  // extract the inputs
  const { project, title, prompts, shouldWait } = req.body

  const username = getUsername(codayOptions, req)

  // make sure to add the thread save title instruction
  const oneShotOptions = {
    ...codayOptions,
    oneshot: true,
    project,
    prompts: [`thread save ${title}`, ...prompts],
  }
  const clientId = Math.random().toString(36).substring(2, 15)

  const client = clientManager.getOrCreate(clientId, null, oneShotOptions, username)

  const interactor = client.getInteractor()

  // start a Coday instance in standalone
  client.startCoday()

  const threadIdSource = client.getThreadId()

  // plug the response on the right end: either starting of Coday or final message
  if (shouldWait) {
    // Wait for the interactor events stream to complete, then get the last message
    const lastEventObservable = interactor.events.pipe(
      filter((event: CodayEvent) => event instanceof MessageEvent && event.role === 'assistant' && !!event.name)
    )
    // Assume if the run has ended, the threadId is defined
    lastValueFrom(lastEventObservable.pipe(withLatestFrom(threadIdSource)))
      .then(([lastEvent, threadId]) => {
        res.status(200).send({ threadId, lastEvent })
      })
      .catch((error) => {
        console.error('Error waiting for webhook completion:', error)
        res.status(500).send({ error: 'Webhook processing failed' })
      })
  } else {
    // return asap with the id of the thread
    firstValueFrom(threadIdSource).then((threadId) => res.status(201).send({ threadId }))
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

function getUsername(codayOptions: CodayOptions, req: express.Request): string {
  return codayOptions.noAuth ? os.userInfo().username : (req.headers[EMAIL_HEADER] as string)
}

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

// Implement SSE for Heartbeat
app.get('/events', (req: express.Request, res: express.Response) => {
  const clientId = req.query.clientId as string

  debugLog('SSE', `New connection request for client ${clientId}`)

  // handle username header coming from auth (or local frontend) or local in noAuth
  const usernameHeaderValue = getUsername(codayOptions, req)
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

// Start cleanup interval for expired clients
setInterval(() => clientManager.cleanupExpired(), 60000) // Check every minute

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

// Graceful shutdown
process.on('SIGTERM', async () => {
  console.log('Received SIGTERM, shutting down gracefully...')
  if (cleanupService) {
    await cleanupService.stop()
  }
})
