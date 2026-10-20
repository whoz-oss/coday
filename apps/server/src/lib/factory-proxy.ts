import type { RequestHandler } from 'express'
import { createProxyMiddleware } from 'http-proxy-middleware'
import type { UpstreamProxyOptions } from './agentos-proxy'
import { propagateUpstreamAbort } from './proxy-abort'

export const FACTORY_API_PREFIX = '/api/factory'

export const isFactoryApiPath = (p: string): boolean =>
  p === FACTORY_API_PREFIX || p.startsWith(`${FACTORY_API_PREFIX}/`)

/**
 * Proxy for the Factory API. The Factory exposes its API with the /api/factory prefix already, so
 * the complete request path is forwarded unchanged.
 *
 * Mounted at the root with `pathFilter`, NOT `app.use('/api/factory', ...)`:
 * http-proxy-middleware v3 forwards `req.url` (v2 used `req.originalUrl`), and Express strips the
 * mount path from `req.url`. A path-mounted proxy would send /api/factory/workflows upstream as
 * /workflows, a 404 from the Factory backend. Register it with `app.use(createFactoryProxy(...))`.
 *
 * Same behaviour as the AgentOS proxy: caller identity hook and stream abort propagation.
 *
 * @param resolveTarget read at request time, so a managed Factory can change its URL after startup.
 * @param options optional hooks on the outgoing request.
 */
export function createFactoryProxy(resolveTarget: () => string, options: UpstreamProxyOptions = {}): RequestHandler {
  return createProxyMiddleware({
    router: resolveTarget,
    changeOrigin: true,
    pathFilter: isFactoryApiPath,
    on: {
      proxyReq: (proxyReq, req) => options.onProxyReq?.(proxyReq, req),
      proxyRes: propagateUpstreamAbort('FACTORY'),
    },
  })
}
