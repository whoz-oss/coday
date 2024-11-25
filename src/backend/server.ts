import express from "express"
import path from "path"
import {AnswerEvent} from "../shared"
import {ServerClientManager} from "./server-client"

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

// Initialize the client manager
const clientManager = new ServerClientManager()

// POST endpoint for stopping the current run
app.post("/api/stop", (req: express.Request, res: express.Response) => {
  try {
    const clientId = req.query.clientId as string
    const client = clientManager.get(clientId)
    
    if (!client) {
      res.status(404).send("Client not found")
      return
    }
    
    client.stop()
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
    const client = clientManager.get(clientId)
    
    if (!client) {
      res.status(404).send("Client not found")
      return
    }
    
    client.getInteractor().sendEvent(new AnswerEvent(payload))
    
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
  
  const client = clientManager.getOrCreate(clientId, res)
  
  // Handle client disconnect
  req.on("close", () => client.terminate())
  
  // Start Coday if it's a new client
  if (client.startCoday()) {
    console.log(`${new Date().toISOString()} Client ${clientId} connected`)
  }
})

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, next: express.NextFunction) => {
  console.error(err.stack)
  res.status(500).send("Something went wrong!")
})

// Start cleanup interval for expired clients
setInterval(() => clientManager.cleanupExpired(), 60000) // Check every minute

app.listen(PORT, () => {
  console.log(`Server is running on http://localhost:${PORT}`)
})