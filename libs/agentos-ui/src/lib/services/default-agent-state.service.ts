import { inject, Injectable } from '@angular/core'
import { NamespaceDefaultAgentControllerService } from '@whoz-oss/agentos-api-client'
import { catchError, map, Observable, of } from 'rxjs'

/**
 * The agent a message without `@mention` reaches in a new case of a namespace, for the current user, as the
 * server resolves it (namespace default, else platform default, among the agents deployed to the user).
 */
@Injectable({ providedIn: 'root' })
export class DefaultAgentStateService {
  private readonly api = inject(NamespaceDefaultAgentControllerService)

  /** The agent name, or null when none would answer or the answer cannot be read: callers then say nothing. */
  resolve(namespaceId: string): Observable<string | null> {
    return this.api.getNamespaceDefaultAgent(namespaceId).pipe(
      map((response) => response.agentName || null),
      catchError(() => of(null))
    )
  }
}
