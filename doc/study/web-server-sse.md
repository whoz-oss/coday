# Web Server with Server-Sent Events (SSE) for Coday

## Overview

This document provides an example of implementing a web server with Server-Sent Events (SSE) using Express.js. SSE is a
simple and effective way to handle real-time updates from the server to the client by keeping an HTTP connection open.

This document was created in response to an initial request to explore and implement a better UI for Coday beyond the
terminal.

## Example Implementation

### server.ts

```typescript
import express from 'express'
import path from 'path'
import {Request, Response} from 'express'

const app = express()
const PORT = 3000

// SSE endpoint
app.get('/events', (req: Request, res: Response) => {
  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')
  
  // Function to send SSE messages
  const sendSSE = (message: string) => {
    res.write(`data: ${message}\n\n`)
  }
  
  // Example of sending an initial message
  sendSSE(JSON.stringify({message: 'Welcome to Coday!'}))
  
  // Set up an interval to send messages
  const intervalId = setInterval(() => {
    sendSSE(JSON.stringify({message: 'Heartbeat'}))
  }, 10000)
  
  // Clean up on client disconnect
  req.on('close', () => {
    clearInterval(intervalId)
    res.end()
  })
})

// Static files
app.use(express.static(path.join(__dirname, 'public')))

app.listen(PORT, () => {
  console.log(`Server is running at http://localhost:${PORT}`)
})
```

### public/index.html

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Coday Web Interface</title>
</head>
<body>
<h1>Welcome to Coday</h1>
<input type="text" id="command" placeholder="Enter command">
<button onclick="sendCommand()">Send</button>
<pre id="response"></pre>

<script>
    // Establish SSE connection
    const eventSource = new EventSource('/events')

    eventSource.onmessage = (event) => {
        const data = JSON.parse(event.data)
        document.getElementById('response').innerText += `${data.message}\n`
    }

    // Send command to server via HTTP POST
    async function sendCommand() {
        const command = document.getElementById('command').value
        await fetch('/api/command', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({command})
        })
    }
</script>
</body>
</html>
```

## Explanation

1. **Server-Sent Events Endpoint**:
    - The `/events` endpoint sets the appropriate headers (`Content-Type`, `Cache-Control`, `Connection`) for SSE.
    - The `sendSSE` function sends messages to the client.
    - A heartbeat message is sent every 10 seconds to keep the connection alive.
    - The connection is cleaned up when the client disconnects.

2. **Client-side Handling**:
    - The client establishes an SSE connection using `EventSource`.
    - Messages from the server are handled in the `onmessage` event handler.
    - Commands are sent to the server via HTTP POST.

## Conclusion

Using SSE with Express.js provides a simple yet effective way to handle real-time updates for Coday, enhancing the user
experience with minimal complexity.

Document created by Coday. 