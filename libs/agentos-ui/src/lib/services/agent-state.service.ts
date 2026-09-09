import { inject, Injectable } from '@angular/core'
import { AgentConfig, AgentConfigControllerService } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'
import { UserStateService } from './user-state.service'

/**
 * AgentStateService — API layer facade for AgentConfig entities.
 *
 * Follows the two-layer pattern: components never inject AgentConfigControllerService
 * directly. All HTTP calls go through this service.
 *
 * ## Endpoint mapping
 *
 * | Method            | Backend endpoint                      |
 * |-------------------|---------------------------------------|
 * | listEffective()   | POST /api/agent-configs/search        |
 *
 * The `search` endpoint returns agents deployed and accessible to the user
 * within the given namespace — which is exactly the "effective" semantics
 * needed for @-mention autocomplete in the chat composer.
 */
@Injectable({ providedIn: 'root' })
export class AgentStateService {
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly userState = inject(UserStateService)

  /**
   * Returns the list of agent configs available to the current user in a namespace.
   * Used for @-mention autocomplete in the chat composer.
   *
   * Delegates to `POST /api/agent-configs/search` which resolves deployment-based
   * access (user groups + namespace membership) and returns only enabled agents.
   *
   * @param namespaceId  The namespace to resolve agents for.
   */
  listEffective(namespaceId: string): Observable<AgentConfig[]> {
    const user = this.userState.currentUser()
    const userExternalId = user?.externalId ?? user?.email ?? ''
    return this.agentConfigController.searchAgentConfig({ namespaceId, userExternalId })
  }
}
