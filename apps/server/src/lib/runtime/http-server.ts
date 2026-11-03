import { Server } from 'http'

/**
 * Stop listening and end the open connections. `close()` alone only calls back once every connection has ended,
 * and a proxied event stream (case events, namespace invalidations) never ends on its own.
 */
export function closeServer(server: Server): Promise<void> {
  return new Promise((resolve) => {
    server.close(() => resolve())
    server.closeAllConnections()
  })
}
