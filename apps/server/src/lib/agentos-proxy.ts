import type { ClientRequest, IncomingMessage } from 'node:http'
import type { RequestHandler } from 'express'
import { createProxyMiddleware } from 'http-proxy-middleware'
import { propagateUpstreamAbort } from './proxy-abort'

export interface UpstreamProxyOptions {
  /**
   * Called for every outgoing proxy request, before it is sent. Used by the server to forward the
   * caller identity (see ./proxy-identity.ts) and to log the request.
   */
  onProxyReq?: (proxyReq: ClientRequest, req: IncomingMessage) => void
}

/**
 * Proxy for the AgentOS API mounted under /api/agentos.
 *
 * Closes the client connection when AgentOS drops a stream mid-body (see ./proxy-abort.ts).
 *
 * @param resolveTarget read at request time, so a managed AgentOS can change its URL after startup.
 * @param options optional hooks on the outgoing request.
 */
export function createAgentOsProxy(resolveTarget: () => string, options: UpstreamProxyOptions = {}): RequestHandler {
  return createProxyMiddleware({
    router: resolveTarget,
    changeOrigin: true,
    pathRewrite: { '^/api/agentos': '' },
    on: {
      proxyReq: (proxyReq, req) => options.onProxyReq?.(proxyReq, req),
      proxyRes: propagateUpstreamAbort('AGENTOS'),
    },
  })
}
