import express from "express"
import path from "path"
import {ServerInteractor} from "../model/server-interactor"
import {Coday} from "../coday"
import {HeartBeatEvent} from "../shared"

const app = express()
const PORT = process.env.PORT || 3000  // Default port as fallback
const interactor = new ServerInteractor()

// Serve static files from the 'static' directory
app.use(express.static(path.join(__dirname, "../../static")))

// Basic route to test server setup
app.get("/", (req: express.Request, res: express.Response) => {
  res.send("Server is up and running!")
})

// Middleware to parse JSON bodies
app.use(express.json())

// POST endpoint for receiving AnswerEvent messages
app.post("/api/message", (req: express.Request, res: express.Response) => {
  try {
    const payload = req.body
    interactor.addAnswerEvent(
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
  
  res.setHeader("Content-Type", "text/event-stream")
  res.setHeader("Cache-Control", "no-cache")
  res.setHeader("Connection", "keep-alive")
  
  // send all events from Coday to the frontend
  interactor.events.subscribe(event => {
    const data = `data: ${JSON.stringify(event)}\n\n`
    return res.write(data)
  })
  
  const sendHeartbeat = () => {
    try {
      const heartBeatEvent = new HeartBeatEvent({})
      interactor.sendEvent(heartBeatEvent)
    } catch (error) {
      clearInterval(heartbeatInterval)
      console.error("Error sending heartbeat:", error)
      res.end()
      terminate()
    }
  }
  
  // Send initial heartbeat message
  sendHeartbeat()
  
  // Send heartbeat messages at specified intervals
  const heartbeatInterval = setInterval(sendHeartbeat, 10000)
  
  // Handle client disconnect
  req.on("close", () => {
    clearInterval(heartbeatInterval)
    console.log("Client disconnected, stopping heartbeats.")
    res.end()
    console.log("Client disconnect, server close")
    terminate()
  })
  const coday = new Coday(interactor, {oneshot: false})
  coday.run()
})

// Error handling middleware
app.use((err: any, req: express.Request, res: express.Response, next: express.NextFunction) => {
  console.error(err.stack)
  res.status(500).send("Something went wrong!")
})

app.listen(PORT, () => {
  console.log(`Server is running on http://localhost:${PORT}`)
})

function terminate(): void {
  process.exit()
}
