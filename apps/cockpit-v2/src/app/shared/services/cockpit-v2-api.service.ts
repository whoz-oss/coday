import { HttpClient } from '@angular/common/http'
import { Injectable, inject } from '@angular/core'
import { Observable } from 'rxjs'
import { CockpitV2ServiceInfo, CockpitV2Status } from '../models/cockpit-v2.model'

/** Base path of the Cockpit V2 backend API. */
export const COCKPIT_V2_API_BASE = '/api/cockpit-v2'

/**
 * HTTP client for the Cockpit V2 backend API.
 *
 * Server-side, the routes are registered by `registerCockpitV2Routes` in
 * apps/server. Keep the paths in sync with `COCKPIT_V2_ROUTE_BASE`.
 */
@Injectable({ providedIn: 'root' })
export class CockpitV2ApiService {
  private readonly http = inject(HttpClient)

  /** Fetch the service descriptor. */
  getServiceInfo(): Observable<CockpitV2ServiceInfo> {
    return this.http.get<CockpitV2ServiceInfo>(COCKPIT_V2_API_BASE)
  }

  /** Fetch the live status payload. */
  getStatus(): Observable<CockpitV2Status> {
    return this.http.get<CockpitV2Status>(`${COCKPIT_V2_API_BASE}/status`)
  }
}
