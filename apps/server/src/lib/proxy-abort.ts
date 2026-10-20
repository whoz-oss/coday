import type { IncomingMessage, ServerResponse } from 'node:http'
import { debugLog } from './log'

/**
 * Build a `proxyRes` handler that closes the client connection when the upstream response stops
 * before its end.
 *
 * The proxy pipes the upstream response into the client response, and a pipe does not end its
 * destination when the source is destroyed mid-body. AgentOS (and the Factory behind it) close a
 * case events stream abruptly on purpose when the connection's live buffer saturates, expecting the
 * browser to reconnect and replay the durable history. Without this, the browser stays on an open
 * but silent connection: EventSource never fires onerror and never reconnects.
 *
 * @param label log context, e.g. 'AGENTOS' or 'FACTORY'.
 */
export function propagateUpstreamAbort(
  label: string
): (proxyRes: IncomingMessage, req: IncomingMessage, res: ServerResponse) => void {
  return (proxyRes, req, res) => {
    proxyRes.on('close', () => {
      if (proxyRes.complete) return
      debugLog(
        label,
        `[PROXY] upstream closed before the end of ${req.method} ${req.url}, closing the client connection`
      )
      res.destroy()
    })
  }
}
