import type { IncomingMessage, ServerResponse } from 'node:http'
import type { RequestHandler } from 'express'
import { createProxyMiddleware } from 'http-proxy-middleware'
import { debugLog } from './log'

/**
 * Proxy for the AgentOS API mounted under /api/agentos.
 *
 * @param resolveTarget read at request time, so a managed AgentOS can change its URL after startup.
 */
export function createAgentOsProxy(resolveTarget: () => string): RequestHandler {
  return createProxyMiddleware({
    router: resolveTarget,
    changeOrigin: true,
    pathRewrite: { '^/api/agentos': '' },
    on: { proxyRes: propagateUpstreamAbort },
  })
}

/**
 * Close the client connection when the upstream response stops before its end.
 *
 * The proxy pipes the upstream response into the client response, and a pipe does not end its
 * destination when the source is destroyed mid-body. AgentOS closes a case events stream abruptly
 * on purpose when the connection's live buffer saturates, expecting the browser to reconnect and
 * replay the durable history. Without this, the browser stays on an open but silent connection:
 * EventSource never fires onerror and never reconnects.
 */
function propagateUpstreamAbort(proxyRes: IncomingMessage, req: IncomingMessage, res: ServerResponse): void {
  proxyRes.on('close', () => {
    if (proxyRes.complete) return
    debugLog(
      'AGENTOS',
      `[PROXY] upstream closed before the end of ${req.method} ${req.url}, closing the client connection`
    )
    res.destroy()
  })
}
