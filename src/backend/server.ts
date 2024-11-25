import express from "express"
import path from "path"
import {ServerInteractor} from "../model/server-interactor"
import {Coday} from "../coday"
import {AnswerEvent, HeartBeatEvent} from "../shared"

const app = express()
const PORT = process.env.PORT || 3000  // Default port as fallback

// Serve static files from the 'static' directory
app.use(express.static(path.join(__dirname, "../../static")))

// Basic route to test server setup
app.get("/", (req: express.Request, res: express.Response) => {
  res.send("Server is up and running!")
})

// Middleware to parse JSON bodies
app.use(express.json())

// Maintain a dictionary of connected clients
const clients: Record<string, {
  res: express.Response,
  interval: NodeJS.Timeout,
  interactor: ServerInteractor,
  coday?: Coday,
  terminationTimeout?: NodeJS.Timeout,
  lastConnected: number
}> = {}


// POST endpoint for stopping the current run
app.post("/api/stop", (req: express.Request, res: express.Response) => {
  try {
    const clientId = req.query.clientId as string
    const client = clients[clientId]
    
    if (!client) {
      res.status(404).send("Client not found")
      return
    }
    
    if (!client.coday) {
      res.status(400).send("No active Coday instance")
      return
    }
    
    client.coday.stop()
    res.status(200).send("Stop signal sent successfully!")
  } catch (error) {
    console.error("Error processing stop request:", error)
    res.status(500).send("Error processing stop request")
  }
})

// POST endpoint for receiving AnswerEvent messages
app.post("/api/message", (req: express.Request, res: express.Response) => {
  try {
    const payload = req.body
    const clientId = req.query.clientId as string
    const client = clients[clientId]
    
    if (!client) {
      res.status(404).send("Client not found")
      return
    }
    
    client.interactor.sendEvent(new AnswerEvent(payload))
    
    res.status(200).send("Message received successfully!")
  } catch (error) {
    console.error("Error processing AnswerEvent:", error)
    res.status(400).send("Invalid event data!")
  }
})

// Implement SSE for Heartbeat
app.get("/events", (req: express.Request, res: express.Response) => {
  const clientId = req.query.clientId as string
  if (!clientId) {
    res.status(400).send("Client ID is required")
    return
  }
  
  res.setHeader("Content-Type", "text/event-stream")
  res.setHeader("Cache-Control", "no-cache")
  res.setHeader("Connection", "keep-alive")
  
  const interactor = new ServerInteractor(clientId)
  
  // send all events from Coday to the frontend
  interactor.events.subscribe(event => {
    const data = `data: ${JSON.stringify(event)}\n\n`
    return res.write(data)
  })
  
  let coday: Coday
  const sendHeartbeat = () => {
    try {
      const heartBeatEvent = new HeartBeatEvent({})
      interactor.sendEvent(heartBeatEvent)
    } catch (error) {
      console.error("Error sending heartbeat:", error)
      terminate(clientId, coday)
    }
  }
  
  // Send initial heartbeat message
  sendHeartbeat()
  
  // Send heartbeat messages at specified intervals
  const heartbeatInterval = setInterval(sendHeartbeat, 10000)
  
  // If there's an existing client with a pending termination, clear it
  if (clients[clientId]?.terminationTimeout) {
    clearTimeout(clients[clientId].terminationTimeout)
    console.log(`${new Date().toISOString()} Client ${clientId} reconnected, cleared termination`)
    
    // Update response and interval, keep existing interactor and coday
    clients[clientId].res = res
    clients[clientId].interval = heartbeatInterval
    clients[clientId].lastConnected = Date.now()
    delete clients[clientId].terminationTimeout
    
    // Resume existing Coday instance
    return
  }
  
  clients[clientId] = {
    res,
    interval: heartbeatInterval,
    interactor,
    lastConnected: Date.now()
  }
  console.log(`${new Date().toISOString()} Client ${clientId} connected`)
  
  // Handle client disconnect
  req.on("close", () => {
    terminate(clientId, coday)
  })
  coday = new Coday(interactor, {oneshot: false})
  clients[clientId].coday = coday
  coday.run().finally(() => terminate(clientId, coday, true))
})

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, next: express.NextFunction) => {
  console.error(err.stack)
  res.status(500).send("Something went wrong!")
})

app.listen(PORT, () => {
  console.log(`Server is running on http://localhost:${PORT}`)
})

const SESSION_TIMEOUT = 60 * 60 * 1000 // 1 hour in milliseconds

function terminate(clientId: string, coday: Coday | undefined, immediate: boolean = false): void {
  const client = clients[clientId]
  if (!client) return
  
  // Clear any existing heartbeat interval
  clearInterval(client.interval)
  client.res.end()
  
  if (immediate) {
    // Immediate termination
    coday?.kill()
    delete clients[clientId]
    console.log(`${new Date().toISOString()} Client ${clientId} terminated immediately`)
    return
  }
  
  // Stop the Coday instance but keep it alive
  coday?.stop()
  
  // Clear any existing termination timeout
  if (client.terminationTimeout) {
    clearTimeout(client.terminationTimeout)
  }
  
  // Set new termination timeout
  client.terminationTimeout = setTimeout(() => {
    const client = clients[clientId]
    if (client) {
      // Check if the session has been idle for too long
      const idleTime = Date.now() - client.lastConnected
      if (idleTime >= SESSION_TIMEOUT) {
        client.coday?.kill()
        delete clients[clientId]
        console.log(`${new Date().toISOString()} Client ${clientId} session expired after ${Math.round(idleTime / 1000)}s of inactivity`)
      }
    }
  }, SESSION_TIMEOUT)
  
  console.log(`${new Date().toISOString()} Client ${clientId} disconnected, termination scheduled in ${SESSION_TIMEOUT / 1000}s`)
}
