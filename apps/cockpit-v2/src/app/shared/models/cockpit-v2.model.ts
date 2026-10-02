/**
 * Shared models for the Cockpit V2 application.
 *
 * These interfaces mirror the payloads returned by the backend
 * `/api/cockpit-v2` routes (see apps/server/src/app/cockpit-v2).
 */

/** Static description of the Cockpit V2 backend service. */
export interface CockpitV2ServiceInfo {
  service: string
  status: string
  version: string
  endpoints: string[]
}

/** Live status payload returned by `GET /api/cockpit-v2/status`. */
export interface CockpitV2Status {
  service: string
  status: string
  version: string
  timestamp: string
}
