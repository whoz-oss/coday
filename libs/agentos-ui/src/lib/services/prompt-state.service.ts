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
 */
@Injectable({ providedIn: 'root' })
export class PromptStateService {
  private readonly promptController = inject(PromptControllerService)

  getById(id: string): Observable<Prompt> {
    return this.promptController.getByIdPrompt(id)
  }

  listPlatform(): Observable<Prompt[]> {
    return this.promptController.listPrompt('PLATFORM')
  }

  listByNamespace(namespaceId: string): Observable<Prompt[]> {
    return this.promptController.listPrompt('NAMESPACE', namespaceId)
  }

  listPlatform(): Observable<Prompt[]> {
    return this.promptController.listPlatformPrompt()
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
   */
  listEffective(namespaceId: string): Observable<Prompt[]> {
    return this.promptController.effectivePrompt(namespaceId)
  }
}
