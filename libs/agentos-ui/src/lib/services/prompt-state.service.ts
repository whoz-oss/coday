import { inject, Injectable } from '@angular/core'
import { Prompt, PromptControllerService } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * PromptStateService — API layer facade for Prompt entities.
 *
 * Follows the two-layer pattern: components never inject PromptControllerService
 * directly. All HTTP calls go through this service.
 *
 * This service is intentionally thin — it delegates 1:1 to the controller.
 * State management (refresh$, caching) lives in the consuming components for now,
 * consistent with the NamespacePromptsComponent pattern.
 *
 * ## Endpoint mapping (post-OpenAPI regen)
 *
 * | Old (GET)                          | New (POST)                          |
 * |------------------------------------|-------------------------------------|
 * | listPrompt('PLATFORM')             | searchPrompt({ namespaceId: null }) |
 * | listPrompt('NAMESPACE', nsId)      | searchPrompt({ namespaceId: nsId }) |
 * | findEffectiveByNamespaceIdPrompt() | effectivePrompt({ ... })            |
 */
@Injectable({ providedIn: 'root' })
export class PromptStateService {
  private readonly promptController = inject(PromptControllerService)

  getById(id: string): Observable<Prompt> {
    return this.promptController.getByIdPrompt(id)
  }

  /** Platform-level prompts (namespaceId = null, userId = null). */
  listPlatform(): Observable<Prompt[]> {
    return this.promptController.searchPrompt({ namespaceId: null, userId: null })
  }

  /** Namespace-shared prompts (namespaceId = nsId, userId = null). */
  listByNamespace(namespaceId: string): Observable<Prompt[]> {
    return this.promptController.searchPrompt({ namespaceId, userId: null })
  }

  create(payload: Prompt): Observable<Prompt> {
    return this.promptController.createPrompt(payload)
  }

  update(id: string, payload: Prompt): Observable<Prompt> {
    return this.promptController.updatePrompt(id, payload)
  }

  delete(id: string): Observable<unknown> {
    return this.promptController.deletePrompt(id)
  }

  /**
   * Returns the effective (merged) prompt list for a namespace+user context.
   * Used for slash-command autocomplete in the chat composer.
   *
   * @param namespaceId  The namespace to resolve prompts for.
   * @param userId       The current user's AgentOS UUID.
   * @param agentConfigId  Optional filter — only prompts linked to this agent.
   */
  listEffective(namespaceId: string, userId: string, agentConfigId?: string): Observable<Prompt[]> {
    return this.promptController.effectivePrompt({
      namespaceId,
      userId,
      ...(agentConfigId ? { agentConfigId } : {}),
    })
  }
}
