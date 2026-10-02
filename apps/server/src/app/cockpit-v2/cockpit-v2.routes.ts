import express from 'express'
import { debugLog } from '../../lib/log'
import { getCodayVersion } from '../../lib/version'

/**
 * Cockpit V2 REST API Routes
 *
 * Exposes the service/status endpoints consumed by the `cockpit-v2` Angular
 * application. All endpoints are grouped under the `/api/cockpit-v2` prefix so
 * they never collide with the legacy client routes.
 *
 * Endpoints:
 * - GET /api/cockpit-v2         - Service descriptor (name, version, endpoints)
 * - GET /api/cockpit-v2/status  - Live status payload (used for health checks)
 */

/** Base path for every Cockpit V2 API endpoint. */
export const COCKPIT_V2_ROUTE_BASE = '/api/cockpit-v2'

/** Static description of the Cockpit V2 backend service. */
export interface CockpitV2ServiceInfo {
  service: 'cockpit-v2'
  status: 'ok'
  version: string
  endpoints: string[]
}

/** Live status payload returned by the status endpoint. */
export interface CockpitV2Status {
  service: 'cockpit-v2'
  status: 'ok'
  version: string
  timestamp: string
}

/**
 * Build the service descriptor payload.
 *
 * @param version - Server version, defaulting to the runtime-resolved version.
 */
export function buildCockpitV2ServiceInfo(version: string = getCodayVersion()): CockpitV2ServiceInfo {
  return {
    service: 'cockpit-v2',
    status: 'ok',
    version,
    endpoints: [`GET ${COCKPIT_V2_ROUTE_BASE}`, `GET ${COCKPIT_V2_ROUTE_BASE}/status`],
  }
}

/**
 * Build the live status payload.
 *
 * @param version - Server version, defaulting to the runtime-resolved version.
 * @param now     - Timestamp source (injectable for deterministic tests).
 */
export function buildCockpitV2Status(version: string = getCodayVersion(), now: Date = new Date()): CockpitV2Status {
  return {
    service: 'cockpit-v2',
    status: 'ok',
    version,
    timestamp: now.toISOString(),
  }
}

/**
 * Register the Cockpit V2 API routes on the Express application.
 *
 * @param app           - Express application instance.
 * @param getUsernameFn - Optional username resolver, used only for logging.
 */
export function registerCockpitV2Routes(
  app: express.Application,
  getUsernameFn?: (req: express.Request) => string
): void {
  /**
   * GET /api/cockpit-v2
   * Service descriptor consumed by the CockpitV2ApiService.
   */
  app.get(COCKPIT_V2_ROUTE_BASE, (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn?.(req)
      debugLog('COCKPIT_V2', `GET service info${username ? ` (user: ${username})` : ''}`)
      res.status(200).json(buildCockpitV2ServiceInfo())
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      console.error('Error retrieving cockpit-v2 service info:', error)
      res.status(500).json({ error: `Failed to retrieve cockpit-v2 service info: ${errorMessage}` })
    }
  })

  /**
   * GET /api/cockpit-v2/status
   * Live status payload used for health checks.
   */
  app.get(`${COCKPIT_V2_ROUTE_BASE}/status`, (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn?.(req)
      debugLog('COCKPIT_V2', `GET status${username ? ` (user: ${username})` : ''}`)
      res.status(200).json(buildCockpitV2Status())
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      console.error('Error retrieving cockpit-v2 status:', error)
      res.status(500).json({ error: `Failed to retrieve cockpit-v2 status: ${errorMessage}` })
    }
  })

  debugLog('COCKPIT_V2', `Cockpit V2 routes registered on ${COCKPIT_V2_ROUTE_BASE}`)
}
