import express from 'express'
import path from 'path'
import { ServerClientManager } from './server-client'
/* eslint-disable @nx/enforce-module-boundaries */
import { AnswerEvent } from '@coday/coday-events'
import { parseCodayOptions } from '@coday/options'
import * as os from 'node:os'
import { debugLog } from './log'

const app = express()
const DEFAULT_PORT = process.env.PORT ? parseInt(process.env.PORT) : 3000

// Function to find the next available port
async function findAvailablePort(startPort: number, maxAttempts = 10): Promise<number> {
  const net = await import('node:net')

  return new Promise((resolve, reject) => {
    function checkPort(port: number, attempts: number) {
      const server = net.createServer()

      server.listen(port, () => {
        server.close(() => {
          debugLog('PORT', `Port ${port} is available`)
          resolve(port)
        })
      })

      server.on('error', (err: NodeJS.ErrnoException) => {
        if (err.code === 'EADDRINUSE') {
          if (attempts > 0) {
            debugLog('PORT', `Port ${port} is in use, trying next`)
            checkPort(port + 1, attempts - 1)
          } else {
            reject(new Error(`Could not find an available port starting from ${startPort}`))
          }
        } else {
          reject(err)
        }
      })
    }

    checkPort(startPort, maxAttempts)
  })
}

// Dynamically find an available port
const PORT_PROMISE = findAvailablePort(DEFAULT_PORT)
const EMAIL_HEADER = 'x-forwarded-email'

// Parse options once for all clients
const codayOptions = parseCodayOptions()
debugLog('INIT', 'Coday options:', codayOptions)
// Serve static files from the 'static' directory
app.use(express.static(path.join(__dirname, '../client')))

// Basic route to test server setup
app.get('/', (req: express.Request, res: express.Response) => {
  res.send('Server is up and running!')
})

// Middleware to parse JSON bodies
app.use(express.json())

// Initialize the client manager
const clientManager = new ServerClientManager()

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
  const usernameHeaderValue = codayOptions.noAuth ? os.userInfo().username : req.headers[EMAIL_HEADER]
  debugLog('SSE', `Connection started, clientId: ${clientId}, username: ${usernameHeaderValue}`)
  if (!usernameHeaderValue || !(typeof usernameHeaderValue === 'string')) {
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
PORT_PROMISE.then((PORT) => {
  app.listen(PORT, () => {
    console.log(`Server is running on http://localhost:${PORT}`)
  })
}).catch((error) => {
  console.error('Failed to start server:', error)
  process.exit(1)
})
