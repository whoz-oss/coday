import express from "express"
import path from "path"
import {ServerInteractor} from "../model/server-interactor"
import {Coday} from "../coday"
import {HeartBeatEvent} from "../shared"

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
const clients: Record<string, { res: express.Response, interval: NodeJS.Timeout, interactor: ServerInteractor }> = {}


// POST endpoint for receiving AnswerEvent messages
app.post("/api/message", (req: express.Request, res: express.Response) => {
  try {
    const payload = req.body
    const clientId = req.query.clientId as string
    
    clients[clientId].interactor.addAnswerEvent(
      payload.answer ?? "",
      payload.parentKey
    )
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
  clients[clientId] = {res, interval: heartbeatInterval, interactor}
  console.log(`${new Date().toISOString()} Client ${clientId} connected`)
  
  // Handle client disconnect
  req.on("close", () => {
    terminate(clientId, coday)
  })
  coday = new Coday(interactor, {oneshot: false})
  coday.run().finally(() => terminate(clientId, coday))
})

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, next: express.NextFunction) => {
  console.error(err.stack)
  res.status(500).send("Something went wrong!")
})

app.listen(PORT, () => {
  console.log(`Server is running on http://localhost:${PORT}`)
})

function terminate(clientId: string, coday: Coday | undefined): void {
  const client = clients[clientId]
  coday?.kill()
  if (client) {
    clearInterval(client.interval)
    client.res.end()
    delete clients[clientId]
    console.log(`${new Date().toISOString()} Client ${clientId} disconnected`)
  }
}
